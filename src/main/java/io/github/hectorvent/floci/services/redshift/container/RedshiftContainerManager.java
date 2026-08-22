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
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    public RedshiftContainerHandle start(String clusterIdentifier, String masterUsername, String masterPassword) {
        String image = config.services().redshift().imageVersion();
        String containerName = "floci-redshift-" + clusterIdentifier;

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
        
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        containers.put(clusterIdentifier, handle);
        return handle;
    }

    public void stop(String clusterIdentifier) {
        containers.remove(clusterIdentifier);
        String containerName = "floci-redshift-" + clusterIdentifier;
        lifecycleManager.removeIfExists(containerName);
    }

    public Optional<RedshiftContainerHandle> getContainer(String clusterIdentifier) {
        return Optional.ofNullable(containers.get(clusterIdentifier));
    }

    public String takeSnapshot(String clusterIdentifier, String username, String dbname) {
        RedshiftContainerHandle handle = containers.get(clusterIdentifier);
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }

        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        String effectiveDb = (dbname != null && !dbname.isBlank()) ? dbname : "dev";

        String[] cmd = new String[]{"pg_dump", "-U", effectiveUser, effectiveDb};
        try {
            ExecResult result = execInContainer(handle.getContainerId(), cmd, 30);
            if (result.exitCode() != 0) {
                LOG.warnv("pg_dump failed for cluster {0} (exit {1}): {2}", clusterIdentifier, result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to create snapshot for cluster " + clusterIdentifier + ": " + result.stderr(), 500);
            }
            return result.stdout();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error executing pg_dump for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to execute pg_dump for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public String takeSnapshot(String clusterIdentifier, String username) {
        return takeSnapshot(clusterIdentifier, username, "dev");
    }

    public String createSnapshot(Cluster cluster) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        return takeSnapshot(cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev");
    }

    public void restoreSnapshot(String clusterIdentifier, String username, String dbname, String sqlDump) {
        RedshiftContainerHandle handle = containers.get(clusterIdentifier);
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }

        if (sqlDump == null || sqlDump.isBlank()) {
            LOG.infov("Empty snapshot dump for cluster {0}, skipping restore", clusterIdentifier);
            return;
        }

        String effectiveUser = (username != null && !username.isBlank()) ? username : "postgres";
        String effectiveDb = (dbname != null && !dbname.isBlank()) ? dbname : "dev";

        try {
            byte[] tar = buildSingleFileTar("restore.sql", sqlDump.getBytes(StandardCharsets.UTF_8), 0644);
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(handle.getContainerId())
                    .withRemotePath("/tmp")
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .exec();

            String[] cmd = new String[]{"psql", "-U", effectiveUser, "-d", effectiveDb, "-f", "/tmp/restore.sql"};
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

    public void restoreSnapshot(String clusterIdentifier, String username, String sqlDump) {
        restoreSnapshot(clusterIdentifier, username, "dev", sqlDump);
    }

    public void restoreSnapshot(Cluster cluster, String sqlDump) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        restoreSnapshot(cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev", sqlDump);
    }

    private ExecResult execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = lifecycleManager.getDockerClient().execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Closeable callback = lifecycleManager.getDockerClient().execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                ByteArrayOutputStream target = (frame.getStreamType() == StreamType.STDERR) ? stderr : stdout;
                try {
                    target.write(payload);
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
                return new ExecResult(-1, stdout.toString(StandardCharsets.UTF_8), "Timed out after " + timeoutSeconds + "s");
            }
            Long exitCode = lifecycleManager.getDockerClient().inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(
                    exitCode != null ? exitCode : -1,
                    stdout.toString(StandardCharsets.UTF_8),
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

    public record ExecResult(long exitCode, String stdout, String stderr) {}
}
