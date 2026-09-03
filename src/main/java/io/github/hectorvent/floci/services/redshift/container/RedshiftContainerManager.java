package io.github.hectorvent.floci.services.redshift.container;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RedshiftContainerManager {

    private static final Logger LOG = Logger.getLogger(RedshiftContainerManager.class);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final Map<String, RedshiftContainerHandle> containers = new ConcurrentHashMap<>();

    @Inject
    public RedshiftContainerManager(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer,
                                    ContainerDetector containerDetector,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    public RedshiftContainerHandle start(String accountId, String clusterIdentifier, String masterUsername, String masterPassword) {
        String image = config.services().redshift().imageVersion();
        String containerName = containerName(accountId, clusterIdentifier);

        List<String> envVars = List.of(
                "POSTGRES_USER=" + masterUsername,
                "POSTGRES_PASSWORD=" + masterPassword,
                "POSTGRES_DB=dev"
        );

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(envVars)
                .withDockerNetwork(config.services().redshift().dockerNetwork())
                .withLogRotation();

        int enginePort = 5432; // Default postgres port
        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(enginePort);
        } else {
            specBuilder.withExposedPort(enginePort);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(enginePort);

        RedshiftContainerHandle handle = new RedshiftContainerHandle(
                info.containerId(), clusterIdentifier, endpoint.host(), endpoint.port());

        try {
            Closeable stream = logStreamer.attach(info.containerId(), "/floci/redshift", clusterIdentifier, "us-east-1", "redshift:" + clusterIdentifier);
            handle.setLogStream(stream);
        } catch (Exception e) {
            LOG.warnv("Failed to stream logs for {0}", containerName);
        }

        waitForReady(containerName, info.containerId(), masterUsername, "dev");

        containers.put(containerKey(accountId, clusterIdentifier), handle);
        return handle;
    }

    /**
     * Starts a cluster's container the same way {@link #start}, except that a container
     * already running under this cluster's name (e.g. Floci's own process restarted but
     * Docker did not) is adopted instead of destroyed and recreated. The container has no
     * persistent volume, so a blind recreate would silently discard the cluster's data;
     * adopting preserves it whenever the physical container survived. If no such container
     * exists (first start, or the container itself was removed), this falls back to
     * {@link #start} — in that case the previous data is unrecoverable, matching this
     * project's decision not to back Redshift containers with a Docker volume.
     */
    public RedshiftContainerHandle adoptOrStart(String accountId, String clusterIdentifier, String masterUsername, String masterPassword) {
        String containerName = containerName(accountId, clusterIdentifier);
        var existing = lifecycleManager.findByName(containerName);
        if (existing.isEmpty()) {
            LOG.warnv("No surviving container for cluster {0}; starting a fresh empty one — the previous"
                    + " contents are not recoverable (no Docker volume backs Redshift containers)", clusterIdentifier);
            return start(accountId, clusterIdentifier, masterUsername, masterPassword);
        }

        LOG.infov("Adopting existing container {0} for cluster {1} to avoid discarding its data",
                containerName, clusterIdentifier);
        int enginePort = 5432;
        ContainerInfo info = lifecycleManager.adopt(existing.get().getId(), List.of(enginePort));
        EndpointInfo endpoint = info.getEndpoint(enginePort);

        RedshiftContainerHandle handle = new RedshiftContainerHandle(
                info.containerId(), clusterIdentifier, endpoint.host(), endpoint.port());
        try {
            Closeable stream = logStreamer.attach(info.containerId(), "/floci/redshift", clusterIdentifier, "us-east-1", "redshift:" + clusterIdentifier);
            handle.setLogStream(stream);
        } catch (Exception e) {
            LOG.warnv("Failed to stream logs for {0}", containerName);
        }

        waitForReady(containerName, info.containerId(), masterUsername, "dev");

        containers.put(containerKey(accountId, clusterIdentifier), handle);
        return handle;
    }

    public void stop(String accountId, String clusterIdentifier) {
        containers.remove(containerKey(accountId, clusterIdentifier));
        lifecycleManager.removeIfExists(containerName(accountId, clusterIdentifier));
    }

    public Optional<RedshiftContainerHandle> getContainer(String accountId, String clusterIdentifier) {
        return Optional.ofNullable(containers.get(containerKey(accountId, clusterIdentifier)));
    }

    /** Distinguishes clusters that share an identifier across accounts, e.g. for the in-memory map key and Docker container name. */
    private static String containerKey(String accountId, String clusterIdentifier) {
        return accountId + "/" + clusterIdentifier;
    }

    private static String containerName(String accountId, String clusterIdentifier) {
        return "floci-redshift-" + accountId + "-" + clusterIdentifier;
    }

    public void takeSnapshot(String accountId, String clusterIdentifier, String username, String dbname, Path outputFile) {
        RedshiftContainerHandle handle = containers.get(containerKey(accountId, clusterIdentifier));
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }

        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        String effectiveDb = (dbname != null && !dbname.isBlank()) ? dbname : "dev";

        String[] cmd = new String[]{"pg_dump", "-U", effectiveUser, effectiveDb, "-f", "/tmp/dump.sql"};
        try {
            ExecResult result = execInContainer(handle.getContainerId(), cmd, 30);
            if (result.exitCode() != 0) {
                LOG.warnv("pg_dump failed for cluster {0} (exit {1}): {2}", clusterIdentifier, result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to create snapshot for cluster " + clusterIdentifier + ": " + result.stderr(), 500);
            }

            try (InputStream in = lifecycleManager.getDockerClient().copyArchiveFromContainerCmd(handle.getContainerId(), "/tmp/dump.sql").exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                tar.getNextTarEntry();
                Files.copy(tar, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error executing pg_dump for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to execute pg_dump for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public void takeSnapshot(String accountId, String clusterIdentifier, String username, Path outputFile) {
        takeSnapshot(accountId, clusterIdentifier, username, "dev", outputFile);
    }

    public void alterUserPassword(String accountId, String clusterIdentifier, String username, String newPassword) {
        RedshiftContainerHandle handle = containers.get(containerKey(accountId, clusterIdentifier));
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }
        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        // Validate effectiveUser against safe SQL identifier pattern to prevent SQL injection.
        // psql -c sends the query string to Postgres, which allows multiple ;-separated statements,
        // so even argv-escaping doesn't protect against a username like "postgres; DROP TABLE ..."
        if (!effectiveUser.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new AwsException("InvalidParameterValue", "Username must be a valid SQL identifier", 400);
        }
        // AWS ModifyCluster rejects ', ", \, / and @ in MasterUserPassword and enforces an
        // 8-64 length plus at least one uppercase, one lowercase and one digit — we enforce
        // the same set for parity. The single-quote rejection is doubly load-bearing: the
        // password is spliced into a psql -c SQL literal here, so ' would also break that
        // literal — but it is AWS-correct regardless, so do not "fix" it away by escaping.
        if (newPassword != null) {
            for (char forbidden : new char[]{'\'', '"', '\\', '/', '@'}) {
                if (newPassword.indexOf(forbidden) >= 0) {
                    throw new AwsException("InvalidParameterValue",
                            "MasterUserPassword must not contain ', \", \\, / or @", 400);
                }
            }
            if (newPassword.length() < 8 || newPassword.length() > 64) {
                throw new AwsException("InvalidParameterValue",
                        "MasterUserPassword must be between 8 and 64 characters in length", 400);
            }
            if (!newPassword.matches(".*[A-Z].*") || !newPassword.matches(".*[a-z].*")
                    || !newPassword.matches(".*[0-9].*")) {
                throw new AwsException("InvalidParameterValue",
                        "MasterUserPassword must contain at least one uppercase letter, one lowercase letter and one number", 400);
            }
        }
        String sql = "ALTER USER " + effectiveUser + " PASSWORD '" + newPassword + "'";
        String[] cmd = new String[]{"psql", "-U", effectiveUser, "-d", "dev", "-c", sql};
        try {
            ExecResult result = execInContainer(handle.getContainerId(), cmd, 15);
            if (result.exitCode() != 0) {
                // Deliberately omit result.stderr() from the log and the exception: psql echoes
                // the failing ALTER USER statement, which contains the password literal.
                LOG.warnv("ALTER USER command failed for cluster {0} (exit {1})", clusterIdentifier, result.exitCode());
                throw new AwsException("InternalFailure", "Failed to change master password for cluster " + clusterIdentifier, 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error changing master password for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to change master password for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public void createSnapshot(String accountId, Cluster cluster, Path outputFile) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        takeSnapshot(accountId, cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev", outputFile);
    }

    public void restoreSnapshot(String accountId, String clusterIdentifier, String username, String dbname, Path sqlDumpFile) {
        RedshiftContainerHandle handle = containers.get(containerKey(accountId, clusterIdentifier));
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }

        if (sqlDumpFile == null || !Files.exists(sqlDumpFile)) {
            LOG.infov("Empty snapshot dump for cluster {0}, skipping restore", clusterIdentifier);
            return;
        }

        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        String effectiveDb = (dbname != null && !dbname.isBlank()) ? dbname : "dev";
        String fileName = sqlDumpFile.getFileName().toString();

        try {
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(handle.getContainerId())
                    .withHostResource(sqlDumpFile.toString())
                    .withRemotePath("/tmp")
                    .exec();

            String[] cmd = new String[]{"psql", "-U", effectiveUser, "-d", effectiveDb, "-f", "/tmp/" + fileName};
            ExecResult result = execInContainer(handle.getContainerId(), cmd, 60);
            if (result.exitCode() != 0) {
                LOG.warnv("psql restore failed for cluster {0} (exit {1}): {2}", clusterIdentifier, result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to restore snapshot for cluster " + clusterIdentifier + ": " + result.stderr(), 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error restoring snapshot for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to restore snapshot for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public void restoreSnapshot(String accountId, String clusterIdentifier, String username, Path sqlDumpFile) {
        restoreSnapshot(accountId, clusterIdentifier, username, "dev", sqlDumpFile);
    }

    public void restoreSnapshot(String accountId, Cluster cluster, Path sqlDumpFile) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        restoreSnapshot(accountId, cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev", sqlDumpFile);
    }

    private ExecResult execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ExecResult res = execInContainer(containerId, cmd, timeoutSeconds, stdout);
        return new ExecResult(res.exitCode(), stdout.toString(StandardCharsets.UTF_8), res.stderr());
    }

    private ExecResult execInContainer(String containerId, String[] cmd, int timeoutSeconds, OutputStream out) throws Exception {
        String execId = lifecycleManager.getDockerClient().execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Closeable callback = lifecycleManager.getDockerClient().execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                try {
                    if (frame.getStreamType() == StreamType.STDERR) {
                        stderr.write(payload);
                    } else {
                        out.write(payload);
                    }
                } catch (IOException ignored) {
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                LOG.warnv(t, "Container exec {0} failed", execId);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                return new ExecResult(-1, "", "Timed out after " + timeoutSeconds + "s");
            }
            Long exitCode = lifecycleManager.getDockerClient().inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(
                    exitCode != null ? exitCode : -1,
                    "",
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            try {
                callback.close();
            } catch (IOException ignored) {
            }
        }
    }

    private byte[] buildSingleFileTar(String filename, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            TarArchiveEntry entry = new TarArchiveEntry(filename);
            entry.setSize(content.length);
            entry.setMode(mode);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }
    private void waitForReady(String containerName, String containerId, String username, String dbName) {
        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        String[] cmd = {
                "psql",
                "-h", "127.0.0.1",
                "-v", "ON_ERROR_STOP=1",
                "-U", effectiveUser,
                "-d", dbName,
                "-c", "SELECT 1"
        };
        execUntilSuccess(containerName, containerId, cmd, "PostgreSQL readiness check");
    }

    private void execUntilSuccess(String containerName, String containerId, String[] cmd, String description) {
        String lastOutput = "";
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                ExecResult result = execInContainer(containerId, cmd, 5);
                lastOutput = result.stderr();
                if (result.exitCode() == 0) {
                    LOG.infov("Initialized {0} in Redshift container {1}", description, containerName);
                    return;
                }
            } catch (Exception e) {
                lastOutput = e.getMessage();
            }
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing " + description + " in " + containerName, e);
            }
        }
        throw new IllegalStateException("Timed out initializing " + description + " in " + containerName + ": " + lastOutput);
    }

    public record ExecResult(long exitCode, String stdout, String stderr) {}
}
