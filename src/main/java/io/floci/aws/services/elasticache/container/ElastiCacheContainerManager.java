package io.floci.aws.services.elasticache.container;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Manages backend Docker container lifecycle for ElastiCache replication groups.
 * In native (dev) mode, binds container port 6379 to a random host port.
 * In Docker mode, uses the container's internal network IP directly.
 */
@ApplicationScoped
public class ElastiCacheContainerManager {

    private static final Logger LOG = Logger.getLogger(ElastiCacheContainerManager.class);
    private static final int BACKEND_PORT = 6379;
    private static final int SNAPSHOT_SAVE_TIMEOUT_MS = 300_000;

    /**
     * Docker can publish a host port before Valkey inside the container is listening. Without a probe,
     * the auth proxy may connect, forward PING, and block on PONG until the client times out.
     */
    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 100;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;
    private static final byte[] RESP_PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public ElastiCacheContainerManager(ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       ContainerLogStreamer logStreamer,
                                       ContainerDetector containerDetector,
                                       EmulatorConfig config,
                                       RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Attempts {@link #start} and reports the backend as unavailable instead of propagating the
     * failure, when the cause is that no Docker daemon is reachable from Floci: Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start Valkey containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable and no
     *         container was created
     */
    public ElastiCacheContainerHandle tryStart(String groupId, String image, String region) {
        return tryStart(groupId, image, region, null);
    }

    /**
     * {@link #tryStart(String, String, String)} for a backend that starts from a snapshot: see
     * {@link #start(String, String, List, String, String)} for {@code seedRdbBase64}.
     */
    public ElastiCacheContainerHandle tryStart(String groupId, String image, String region,
                                               String seedRdbBase64) {
        try {
            ElastiCacheContainerHandle handle = start(groupId, image, List.of(), region, seedRdbBase64);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            boolean partial = activeContainers.containsKey(groupId);
            stopByGroupId(groupId);
            if (partial) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). ElastiCache metadata "
                        + "operations keep working and replication groups still reach ''available'', "
                        + "but they have no backing Valkey container until a daemon becomes "
                        + "reachable.", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        return lifecycleManager.isDockerReachable();
    }

    public ElastiCacheContainerHandle start(String groupId, String image, String region) {
        return start(groupId, image, List.of(), region);
    }

    /**
     * Starts a backend container for the given resource id, appending {@code extraServerFlags}
     * to the Valkey server command line (via the image's {@code VALKEY_EXTRA_FLAGS} hook).
     * Cluster-mode nodes use this to pass {@code --cluster-enabled} and announce settings.
     */
    public ElastiCacheContainerHandle start(String groupId, String image, List<String> extraServerFlags,
                                            String region) {
        return start(groupId, image, extraServerFlags, region, null);
    }

    /** Whether backends start and snapshot the way an image with no shell or CLI needs. */
    public boolean isShellLessBackend() {
        return config.services().elasticache().shellLessBackend();
    }

    /**
     * {@link #start(String, String, List, String)} that also loads a snapshot at startup, for the
     * shell-less path only: the RDB file is placed in the container's data directory before the
     * server process starts, so it loads the dataset itself and no {@code DEBUG} command is needed.
     * A container that cannot be seeded is removed and reported as a {@link SnapshotRestoreException}.
     *
     * @param seedRdbBase64 a {@link #snapshot} archive to start from, or {@code null} for an empty cache
     */
    public ElastiCacheContainerHandle start(String groupId, String image, List<String> extraServerFlags,
                                            String region, String seedRdbBase64) {
        LOG.infov("Starting ElastiCache backend container for group: {0}", groupId);
        boolean shellLess = isShellLessBackend();
        if (seedRdbBase64 != null && !shellLess) {
            throw new IllegalArgumentException(
                    "A snapshot is loaded at startup only with floci.services.elasticache.shell-less-backend");
        }

        String containerName = containerName(groupId);

        // Remove any stale container with the same name
        lifecycleManager.removeIfExists(containerName);

        // Build container spec. Only publish the backend port to the host in
        // native mode — in Docker mode the JVM reaches the container via its
        // network IP, no host binding needed.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().elasticache().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "elasticache", groupId, regionResolver.getAccountId(), region));
        if (shellLess) {
            // The server flags are the container's arguments, after the image's own entrypoint.
            // Replacing the image's default arguments drops whatever they set, so the listening
            // port and protected mode, which the proxy and the readiness probe rely on, are
            // stated here. DEBUG is not enabled: a snapshot is restored by file, before start.
            List<String> arguments = new ArrayList<>(List.of("--port", String.valueOf(BACKEND_PORT),
                    "--protected-mode", "no", "--loglevel", "verbose"));
            arguments.addAll(extraServerFlags);
            specBuilder.withCmd(arguments);
        } else {
            // DEBUG is enabled for connections from inside the container only, so a snapshot restore
            // can reload the dataset (DEBUG RELOAD NOSAVE); clients reach the server through the proxy.
            StringBuilder serverFlags = new StringBuilder("--loglevel verbose --enable-debug-command local");
            for (String flag : extraServerFlags) {
                serverFlags.append(' ').append(flag);
            }
            specBuilder.withEnv("VALKEY_EXTRA_FLAGS", serverFlags.toString());
        }

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(BACKEND_PORT);
        } else {
            specBuilder.withExposedPort(BACKEND_PORT);
        }

        ContainerSpec spec = specBuilder.build();

        // Create and start container
        String seededDirectory = null;
        ContainerInfo info;
        if (seedRdbBase64 == null) {
            info = lifecycleManager.createAndStart(spec);
        } else {
            String[] placedIn = new String[1];
            info = lifecycleManager.createAndStart(spec, id -> placedIn[0] = placeSnapshot(id, seedRdbBase64));
            seededDirectory = placedIn[0];
        }
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);

        LOG.infov("ElastiCache backend for group {0}: {1}", groupId, endpoint);

        ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                info.containerId(), groupId, endpoint.host(), endpoint.port());
        try {
            handle.setNetworkIp(lifecycleManager.resolveContainerNetworkIp(
                    info.containerId(), config.services().elasticache().dockerNetwork().orElse(null)));
        } catch (RuntimeException e) {
            LOG.warnv("Could not resolve network IP for ElastiCache container {0}: {1}",
                    info.containerId(), e.getMessage());
        }
        activeContainers.put(groupId, handle);

        // Attach log streaming
        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/elasticache/cluster/" + groupId + "/engine-log";
        String logStream = logStreamer.generateLogStreamName(shortId);

        Closeable logHandle = logStreamer.attachPolled(
                info.containerId(), logGroup, logStream, region, "elasticache:" + groupId);
        handle.setLogStream(logHandle);

        if (seededDirectory == null) {
            waitForBackendReady(groupId, endpoint.host(), endpoint.port(), () -> false);
        } else {
            // A server that cannot load the snapshot exits instead of starting empty; noticing that
            // saves waiting out the whole readiness deadline.
            try {
                waitForBackendReady(groupId, endpoint.host(), endpoint.port(),
                        () -> lifecycleManager.probeContainer(info.containerId()) == ContainerLiveness.NOT_RUNNING);
            } catch (RuntimeException e) {
                throw new SnapshotRestoreException("the backend did not become ready with the snapshot in "
                        + "place: " + e.getMessage(), e);
            }
            verifySnapshotDirectory(endpoint.host(), endpoint.port(), seededDirectory,
                    config.services().elasticache().shellLessBackendDbfilename());
        }

        return handle;
    }

    /** A snapshot that could not be placed in, or loaded by, a starting backend. */
    public static class SnapshotRestoreException extends RuntimeException {
        public SnapshotRestoreException(String message, Throwable cause) {
            super(message, cause);
        }

        public SnapshotRestoreException(String message) {
            super(message);
        }
    }

    /**
     * Puts the snapshot into the data directory of a created, not yet started container, under the
     * file name the server loads at startup. The data directory is the one a server started without
     * {@code --dir} uses, the image's working directory.
     *
     * @return the directory the file was placed in
     */
    private String placeSnapshot(String containerId, String rdbBase64) {
        try {
            byte[] rdb = Base64.getDecoder().decode(rdbBase64);
            DockerClient docker = lifecycleManager.getDockerClient();
            String workingDir = docker.inspectContainerCmd(containerId).exec().getConfig().getWorkingDir();
            String directory = workingDir == null || workingDir.isBlank() ? "/" : workingDir;
            RetryingTarCopier.copyBytes(docker, containerId, directory,
                    config.services().elasticache().shellLessBackendDbfilename(), rdb, 0644);
            return directory;
        } catch (RuntimeException e) {
            throw new SnapshotRestoreException("could not place the snapshot in the backend's data directory: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Checks over RESP that the running server uses the directory and file name the snapshot was placed
     * under, since a server that reads another file would start empty and the restore would look
     * successful.
     */
    private static void verifySnapshotDirectory(String host, int port, String placedIn,
                                                String placedFileName) {
        try (ValkeyClusterFormation.RespClient client = new ValkeyClusterFormation.RespClient(host, port)) {
            String directory = configValue(client, "dir");
            if (directory == null || !directory.equals(placedIn)) {
                throw new SnapshotRestoreException("the backend's data directory is " + directory
                        + ", not " + placedIn + " where the snapshot was placed");
            }
            // A server that reports another snapshot file name loads that one and starts empty.
            String fileName = configValue(client, "dbfilename");
            String placedAs = placedFileName;
            if (fileName != null && !fileName.isBlank() && !fileName.equals(placedAs)) {
                throw new SnapshotRestoreException("the backend loads its snapshot from " + fileName
                        + ", not " + placedAs + " where the snapshot was placed; set "
                        + "floci.services.elasticache.shell-less-backend-dbfilename to " + fileName);
            }
        } catch (IOException e) {
            throw new SnapshotRestoreException("could not confirm the backend's data directory: "
                    + e.getMessage(), e);
        }
    }

    private static String configValue(ValkeyClusterFormation.RespClient client, String parameter)
            throws IOException {
        Object reply = client.call("CONFIG", "GET", parameter);
        if (reply instanceof List<?> items && items.size() >= 2 && items.get(1) != null) {
            return items.get(1).toString();
        }
        return null;
    }

    private static void waitForBackendReady(String groupId, String host, int port,
                                            BooleanSupplier backendExited) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                s.setTcpNoDelay(true);
                s.setSoTimeout(BACKEND_PROBE_CONNECT_MS);
                OutputStream out = s.getOutputStream();
                out.write(RESP_PING);
                out.flush();
                String line = RespLineReader.readAsciiLineCrLf(s.getInputStream());
                if (line.startsWith("+PONG")) {
                    if (attempt > 1) {
                        LOG.infov("ElastiCache backend ready for group {0} after {1} probe attempt(s)", groupId, attempt);
                    }
                    return;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("ElastiCache backend probe for group {0}: unexpected line {1}", groupId, line);
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("ElastiCache backend probe for group {0} attempt {1}: {2}", groupId, attempt, e.getMessage());
                }
            }
            if (backendExited.getAsBoolean()) {
                throw new RuntimeException("ElastiCache backend for group " + groupId + " exited before it was ready");
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for ElastiCache backend " + groupId, ie);
            }
        }
        throw new RuntimeException(
                "ElastiCache backend for group " + groupId + " did not become ready on " + host + ":" + port
                        + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }

    /** The server's own CLI, valkey-cli or redis-cli, run inside its container against the local server. */
    private static final String CLI = "C=$(command -v valkey-cli || command -v redis-cli); ";

    /**
     * A snapshot of the dataset: the server's RDB file, written by SAVE, base64 encoded so it
     * travels through the exec's text output. The CLI exits 0 on an error reply too, so the reply
     * itself must be OK: a SAVE refused while a BGSAVE runs would otherwise hand back the previous
     * RDB file.
     */
    public String snapshot(String containerId) {
        if (isShellLessBackend()) {
            return snapshotWithoutShell(containerId);
        }
        String[] cmd = {"sh", "-c", CLI + "R=$($C SAVE); if [ \"$R\" != OK ]; then echo \"SAVE: $R\" >&2; exit 1; fi;"
                + " base64 -w0 \"$($C CONFIG GET dir | sed -n 2p)/$($C CONFIG GET dbfilename | sed -n 2p)\""};
        ContainerExec.Result result = ContainerExec.run(lifecycleManager.getDockerClient(), containerId, cmd, 300);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("SAVE failed with exit code " + result.exitCode() + ": " + result.stderr());
        }
        return result.stdout().trim();
    }

    /**
     * {@link #snapshot} for a backend with no shell or CLI: {@code SAVE} and the server's
     * {@code dir} and {@code dbfilename} are asked over a RESP connection, and the file is read
     * out of the container through the Docker archive API.
     */
    private String snapshotWithoutShell(String containerId) {
        ElastiCacheContainerHandle handle = activeContainers.values().stream()
                .filter(h -> containerId.equals(h.getContainerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no running backend for container " + containerId));
        String directory;
        String fileName;
        try (ValkeyClusterFormation.RespClient client = new ValkeyClusterFormation.RespClient(
                handle.getHost(), handle.getPort(), SNAPSHOT_SAVE_TIMEOUT_MS)) {
            Object reply = client.call("SAVE");
            if (!"OK".equals(reply)) {
                throw new IllegalStateException("SAVE: " + reply);
            }
            directory = configValue(client, "dir");
            fileName = configValue(client, "dbfilename");
        } catch (IOException e) {
            throw new IllegalStateException("SAVE failed: " + e.getMessage(), e);
        }
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("the backend did not report its data directory");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = config.services().elasticache().shellLessBackendDbfilename();
        }
        String path = directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
        try (InputStream tar = lifecycleManager.getDockerClient()
                .copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream entries = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry = entries.getNextEntry();
            while (entry != null && !entry.isFile()) {
                entry = entries.getNextEntry();
            }
            if (entry == null) {
                throw new IllegalStateException("the snapshot file " + path + " is not in the backend");
            }
            return Base64.getEncoder().encodeToString(entries.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("could not read the snapshot file " + path + ": " + e.getMessage(), e);
        }
    }

    /** Replaces the dataset with a {@link #snapshot} RDB file and reloads it without saving first. */
    public void restore(String containerId, String rdbBase64) {
        byte[] rdb = Base64.getDecoder().decode(rdbBase64);
        RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, "/tmp",
                "floci-restore.rdb", rdb, 0644);
        String[] cmd = {"sh", "-c", CLI + "D=$($C CONFIG GET dir | sed -n 2p); F=$($C CONFIG GET dbfilename | sed -n 2p);"
                + " cp /tmp/floci-restore.rdb \"$D/$F\" && rm -f /tmp/floci-restore.rdb"
                + " && $C DEBUG RELOAD NOSAVE | grep -q OK"};
        ContainerExec.Result result = ContainerExec.run(lifecycleManager.getDockerClient(), containerId, cmd, 300);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("restore failed with exit code " + result.exitCode() + ": "
                    + result.stderr() + result.stdout());
        }
    }

    public void stop(ElastiCacheContainerHandle handle) {
        if (handle == null) {
            return;
        }
        ElastiCacheContainerHandle existing = activeContainers.remove(handle.getGroupId());
        Closeable logStream = handle.getLogStream() != null ? handle.getLogStream()
                : (existing != null ? existing.getLogStream() : null);
        lifecycleManager.stopAndRemove(handle.getContainerId(), logStream);
    }

    /**
     * Stops and removes the backend container for a group by id, if one exists.
     * Used by the service's provisioning rollback: {@link #start} registers the container in
     * {@code activeContainers} <em>before</em> {@link #waitForBackendReady}, so a readiness
     * timeout throws without ever returning the handle to the caller. In that case rollback
     * can't go through {@link #stop} (it has no handle), so it cleans up by id instead. Falls
     * back to the deterministic container name to catch a container that failed before it was
     * registered. Idempotent — a no-op when nothing is running for the id.
     */
    public void stopByGroupId(String groupId) {
        ElastiCacheContainerHandle handle = activeContainers.get(groupId);
        if (handle != null) {
            stop(handle);
            return;
        }
        lifecycleManager.removeIfExists(containerName(groupId));
    }

    private String containerName(String groupId) {
        return ContainerStorageHelper.resourceName(config, "valkey", null, groupId);
    }

    public void stopAll() {
        List<ElastiCacheContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} ElastiCache container(s) on shutdown", handles.size());
        }
        for (ElastiCacheContainerHandle handle : handles) {
            stop(handle);
        }
    }
}
