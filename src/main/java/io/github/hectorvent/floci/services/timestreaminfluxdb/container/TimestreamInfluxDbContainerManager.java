package io.github.hectorvent.floci.services.timestreaminfluxdb.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the InfluxDB 2.x containers that back Timestream for InfluxDB resources. The HTTP
 * listener is published on a host port from the configured range in every topology, because no
 * Floci proxy fronts InfluxDB traffic. Data and the influx CLI configuration written by the
 * image's setup mode live on per-resource volumes, so restarts and parameter changes keep both.
 */
@ApplicationScoped
public class TimestreamInfluxDbContainerManager implements ContainerTeardown {

    static final String SERVICE = "timestream-influxdb";
    static final int INFLUX_PORT = 8086;
    private static final Logger LOG = Logger.getLogger(TimestreamInfluxDbContainerManager.class);
    private static final String DATA_PATH = "/var/lib/influxdb2";
    private static final String CONFIG_PATH = "/etc/influxdb2";
    private static final String CLI_CONFIG_FILE = CONFIG_PATH + "/influx-configs";
    private static final int READINESS_RETRY_MS = 500;
    private static final int EXEC_TIMEOUT_SECONDS = 300;

    public record InfluxDbSetup(String username, String password, String organization, String bucket) {
    }

    public record InfluxDbEndpoint(String containerId, String host, int port) {
    }

    private record RunningContainer(String containerId, int hostPort) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final Map<String, RunningContainer> running = new ConcurrentHashMap<>();

    @Inject
    public TimestreamInfluxDbContainerManager(ContainerBuilder containerBuilder,
                                              ContainerLifecycleManager lifecycleManager,
                                              ContainerDetector containerDetector,
                                              PortAllocator portAllocator,
                                              EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    public InfluxDbEndpoint start(String resourceId, String accountId, String region,
                                  InfluxDbSetup setup, Map<String, String> engineEnvironment) {
        EmulatorConfig.TimestreamInfluxDbServiceConfig serviceConfig = config.services().timestreamInfluxdb();
        String containerName = containerName(resourceId);
        LOG.infov("Starting InfluxDB container {0} for {1} using image {2}",
                containerName, resourceId, serviceConfig.defaultImage());

        lifecycleManager.removeIfExists(containerName);
        releasePort(resourceId);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(serviceConfig.defaultImage())
                .withName(containerName)
                .withDockerNetwork(serviceConfig.dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, resourceId, accountId, region));
        if (setup != null) {
            builder.withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
                    .withEnv("DOCKER_INFLUXDB_INIT_USERNAME", setup.username())
                    .withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", setup.password())
                    .withEnv("DOCKER_INFLUXDB_INIT_ORG", setup.organization())
                    .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", setup.bucket());
        }
        for (Map.Entry<String, String> entry : engineEnvironment.entrySet()) {
            builder.withEnv(entry.getKey(), entry.getValue());
        }

        int hostPort = portAllocator.allocate(serviceConfig.hostPortBase(), serviceConfig.hostPortMax());
        builder.withPortBinding(INFLUX_PORT, hostPort);

        ContainerInfo info;
        try {
            applyVolumes(builder, resourceId);
            info = lifecycleManager.createAndStart(builder.build());
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(containerName);
            portAllocator.release(hostPort);
            throw e;
        }
        running.put(resourceId, new RunningContainer(info.containerId(), hostPort));
        EndpointInfo endpoint = info.getEndpoint(INFLUX_PORT);
        LOG.infov("InfluxDB container {0} for {1} listening on {2} (host port {3})",
                info.containerId(), resourceId, endpoint, String.valueOf(hostPort));
        return new InfluxDbEndpoint(info.containerId(), endpoint.host(), endpoint.port());
    }

    public void waitUntilReady(InfluxDbEndpoint endpoint) {
        int timeoutSeconds = config.services().timestreamInfluxdb().readinessTimeoutSeconds();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy(endpoint)) {
                return;
            }
            try {
                Thread.sleep(READINESS_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for InfluxDB at " + endpoint.host(), e);
            }
        }
        throw new IllegalStateException("InfluxDB at " + endpoint.host() + ":" + endpoint.port()
                + " did not become healthy within " + timeoutSeconds + "s");
    }

    boolean isHealthy(InfluxDbEndpoint endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(
                    "http://" + endpoint.host() + ":" + endpoint.port() + "/health").toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            LOG.debugv("InfluxDB health probe at {0}:{1} not ready: {2}",
                    endpoint.host(), String.valueOf(endpoint.port()), e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public void restart(String containerId) {
        lifecycleManager.getDockerClient().restartContainerCmd(containerId).exec();
    }

    public void stop(String resourceId, String containerId) {
        RunningContainer container = running.remove(resourceId);
        if (container != null) {
            portAllocator.release(container.hostPort());
        }
        String effectiveId = containerId != null ? containerId : container != null ? container.containerId() : null;
        if (effectiveId != null) {
            lifecycleManager.stopAndRemove(effectiveId, null);
        } else {
            lifecycleManager.removeIfExists(containerName(resourceId));
        }
    }

    public void removeStorage(String resourceId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeStorage(config, lifecycleManager, SERVICE, null, resourceId);
            ContainerStorageHelper.removeStorage(config, lifecycleManager, SERVICE, null, resourceId + "-config");
        }
    }

    public void backup(String containerId, String backupId) {
        String workDir = "/tmp/floci-backup-" + backupId;
        execOrThrow(containerId, new String[]{"influx", "backup", workDir}, "influx backup");
        try {
            Path directory = backupDirectory();
            Files.createDirectories(directory);
            copyFromContainer(containerId, workDir, directory.resolve(backupId + ".tar"));
            copyFromContainer(containerId, CLI_CONFIG_FILE, directory.resolve(backupId + "-cli.tar"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store InfluxDB backup " + backupId, e);
        } finally {
            execQuietly(containerId, new String[]{"rm", "-rf", workDir});
        }
    }

    public void restore(String containerId, String backupId) {
        Path directory = backupDirectory();
        String workDir = "/tmp/floci-backup-" + backupId;
        try {
            copyToContainer(containerId, directory.resolve(backupId + ".tar"), "/tmp");
            execOrThrow(containerId, new String[]{"influx", "restore", "--full", workDir}, "influx restore");
            copyToContainer(containerId, directory.resolve(backupId + "-cli.tar"), CONFIG_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore InfluxDB backup " + backupId, e);
        } finally {
            execQuietly(containerId, new String[]{"rm", "-rf", workDir});
        }
    }

    public void deleteBackupArtifacts(String backupId) {
        Path directory = backupDirectory();
        for (String suffix : List.of(".tar", "-cli.tar")) {
            try {
                Files.deleteIfExists(directory.resolve(backupId + suffix));
            } catch (IOException e) {
                LOG.warnv(e, "Failed to delete InfluxDB backup artifact {0}{1}", backupId, suffix);
            }
        }
    }

    @Override
    public void stopManagedContainers() {
        List<String> resourceIds = new ArrayList<>(running.keySet());
        if (!resourceIds.isEmpty()) {
            LOG.infov("Stopping {0} Timestream for InfluxDB container(s) on shutdown", resourceIds.size());
        }
        for (String resourceId : resourceIds) {
            stop(resourceId, null);
        }
    }

    /**
     * Maps the InfluxDBv2 members of a DB parameter group onto the {@code INFLUXD_*} environment
     * variables of the same InfluxDB configuration options. Duration members become InfluxDB
     * duration strings; InfluxDB has no day unit, so days are expressed in hours.
     */
    public static Map<String, String> engineEnvironment(JsonNode influxDbV2Parameters) {
        Map<String, String> environment = new LinkedHashMap<>();
        if (influxDbV2Parameters == null || !influxDbV2Parameters.isObject()) {
            return environment;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = influxDbV2Parameters.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String rendered = value.isObject() ? duration(value) : value.asText();
            environment.put("INFLUXD_" + upperSnake(field.getKey()), rendered);
        }
        return environment;
    }

    private static String duration(JsonNode value) {
        long amount = value.path("value").asLong();
        return switch (value.path("durationType").asText()) {
            case "days" -> (amount * 24) + "h";
            case "hours" -> amount + "h";
            case "minutes" -> amount + "m";
            case "milliseconds" -> amount + "ms";
            default -> amount + "s";
        };
    }

    private static String upperSnake(String camelCase) {
        StringBuilder result = new StringBuilder();
        for (char c : camelCase.toCharArray()) {
            if (Character.isUpperCase(c) && !result.isEmpty()) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }

    private String containerName(String resourceId) {
        return ContainerStorageHelper.resourceName(config, SERVICE, null, resourceId);
    }

    private void releasePort(String resourceId) {
        RunningContainer previous = running.remove(resourceId);
        if (previous != null) {
            portAllocator.release(previous.hostPort());
        }
    }

    private void applyVolumes(ContainerBuilder.Builder builder, String resourceId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(builder, lifecycleManager, config, SERVICE, null, resourceId, DATA_PATH);
            ContainerStorageHelper.applyStorage(builder, lifecycleManager, config, SERVICE, null,
                    resourceId + "-config", CONFIG_PATH);
            return;
        }
        Path base = ContainerStorageHelper.hostResourcePath(config, SERVICE, resourceId);
        String dataDir = base.resolve("data").toAbsolutePath().toString();
        String configDir = base.resolve("config").toAbsolutePath().toString();
        if (!containerDetector.isRunningInContainer()) {
            ContainerStorageHelper.ensureHostDir(dataDir);
            ContainerStorageHelper.ensureHostDir(configDir);
        }
        builder.withBind(dataDir, DATA_PATH).withBind(configDir, CONFIG_PATH);
    }

    private Path backupDirectory() {
        return Path.of(config.storage().persistentPath()).resolve(SERVICE).resolve("backups");
    }

    private void copyFromContainer(String containerId, String containerPath, Path target) throws IOException {
        try (InputStream archive = lifecycleManager.getDockerClient()
                .copyArchiveFromContainerCmd(containerId, containerPath).exec()) {
            Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyToContainer(String containerId, Path archive, String remotePath) throws IOException {
        try (InputStream stream = Files.newInputStream(archive)) {
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(stream)
                    .exec();
        }
    }

    private void execOrThrow(String containerId, String[] command, String description) {
        ExecResult result = exec(containerId, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(description + " failed with exit code " + result.exitCode()
                    + ": " + result.stderr() + result.stdout());
        }
    }

    private void execQuietly(String containerId, String[] command) {
        try {
            exec(containerId, command);
        } catch (RuntimeException e) {
            LOG.debugv("Cleanup command in container {0} failed: {1}", containerId, e.getMessage());
        }
    }

    private ExecResult exec(String containerId, String[] command) {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Closeable callback = dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getPayload() == null) {
                    return;
                }
                try {
                    if (frame.getStreamType() == StreamType.STDERR) {
                        stderr.write(frame.getPayload());
                    } else {
                        stdout.write(frame.getPayload());
                    }
                } catch (IOException e) {
                    LOG.warnv(e, "Failed to read output of container exec {0}", execId);
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                LOG.warnv(throwable, "Container exec {0} failed", execId);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new ExecResult(-1, stdout.toString(StandardCharsets.UTF_8), "timed out");
            }
            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(exitCode != null ? exitCode : -1,
                    stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running command in container " + containerId, e);
        } finally {
            try {
                callback.close();
            } catch (IOException e) {
                LOG.debugv("Failed to close exec callback {0}: {1}", execId, e.getMessage());
            }
        }
    }

    private record ExecResult(long exitCode, String stdout, String stderr) {
    }
}
