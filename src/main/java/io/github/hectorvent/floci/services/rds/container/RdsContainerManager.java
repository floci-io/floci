package io.github.hectorvent.floci.services.rds.container;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manages backend Docker container lifecycle for RDS DB instances and clusters.
 * Starts postgres/mysql/mariadb containers and resolves the backend host:port for the auth proxy.
 */
@ApplicationScoped
public class RdsContainerManager {

    private static final Logger LOG = Logger.getLogger(RdsContainerManager.class);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Map<String, RdsContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public RdsContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               EmulatorConfig config,
                               RegionResolver regionResolver,
                               ServiceConfigAccess serviceConfigAccess) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.serviceConfigAccess = serviceConfigAccess;
    }

    public RdsContainerHandle start(String instanceId, String volumeId, DatabaseEngine engine,
                                    String image, String masterUsername,
                                    String masterPassword, String dbName) {
        LOG.infov("Starting RDS backend container for instance: {0} engine={1}", instanceId, engine);

        int enginePort = engine.defaultPort();
        String containerName = ContainerStorageHelper.resourceName(config, "rds", volumeId, instanceId);

        // Remove any stale container with the same name
        lifecycleManager.removeIfExists(containerName);

        // Build environment variables
        List<String> envVars = buildEnvVars(engine, masterUsername, masterPassword, dbName);

        // Build container spec with bind mounts for persistence. Publish the
        // engine port to the host only in native mode; in Docker mode the auth
        // proxy reaches the DB via the container network.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(envVars)
                .withDockerNetwork(config.services().rds().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(enginePort);
        } else {
            specBuilder.withExposedPort(enginePort);
        }

        // Handle persistence mounting
        addPersistenceMounts(specBuilder, instanceId, volumeId, engine, image);

        // Add engine-specific command
        List<String> cmd = buildContainerCmd(engine);
        if (!cmd.isEmpty()) {
            specBuilder.withCmd(cmd);
        }

        ContainerSpec spec = specBuilder.build();

        // Create and start container
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(enginePort);

        LOG.infov("RDS backend for instance {0}: {1}", instanceId, endpoint);
        initializeEngine(containerName, info.containerId(), engine, masterUsername);

        RdsContainerHandle handle = new RdsContainerHandle(
                info.containerId(), instanceId, endpoint.host(), endpoint.port());
        activeContainers.put(instanceId, handle);

        // Attach log streaming
        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/rds/instance/" + instanceId + "/error";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "rds:" + instanceId);
        handle.setLogStream(logHandle);

        return handle;
    }

    public void stop(RdsContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getInstanceId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopAll() {
        List<RdsContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} RDS container(s) on shutdown", handles.size());
        }
        for (RdsContainerHandle handle : handles) {
            stop(handle);
        }
    }

    private void addPersistenceMounts(ContainerBuilder.Builder specBuilder, String instanceId,
                                      String volumeId, DatabaseEngine engine, String image) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(
                    specBuilder, lifecycleManager, config, "rds", volumeId, instanceId,
                    engineDefaultDataPath(engine, image));
            return;
        }

        // Legacy host-path mode: host-persistent-path is an absolute path
        String hostDataPath = ContainerStorageHelper.hostResourcePath(config, "rds", instanceId).toString();
        if (!containerDetector.isRunningInContainer()) {
            ContainerStorageHelper.ensureHostDir(hostDataPath);
        }
        specBuilder.withBind(hostDataPath, engineDefaultDataPath(engine, image));
    }

    static String engineDefaultDataPath(DatabaseEngine engine, String image) {
        return switch (engine) {
            case POSTGRES -> postgresDataPath(image);
            case MYSQL, MARIADB -> "/var/lib/mysql";
        };
    }

    private static String postgresDataPath(String image) {
        if (postgresImageMajorVersion(image) >= 18) {
            return "/var/lib/postgresql";
        }
        return "/var/lib/postgresql/data";
    }

    private static int postgresImageMajorVersion(String image) {
        if (image == null || image.isBlank()) {
            return -1;
        }
        String reference = image;
        int digestSeparator = reference.indexOf('@');
        if (digestSeparator >= 0) {
            reference = reference.substring(0, digestSeparator);
        }
        int slashSeparator = reference.lastIndexOf('/');
        int tagSeparator = reference.lastIndexOf(':');
        if (tagSeparator < slashSeparator || tagSeparator == reference.length() - 1) {
            return -1;
        }
        String tag = reference.substring(tagSeparator + 1);
        int end = 0;
        while (end < tag.length() && Character.isDigit(tag.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        return Integer.parseInt(tag.substring(0, end));
    }

    private void initializeEngine(String containerName, String containerId, DatabaseEngine engine, String masterUsername) {
        if (engine == DatabaseEngine.POSTGRES) {
            initializePostgresIamRole(containerName, containerId, masterUsername);
        }
    }

    static String postgresIamRoleInitSql() {
        return """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_iam') THEN
                        CREATE ROLE rds_iam;
                    END IF;
                END
                $$;
                """;
    }

    private void initializePostgresIamRole(String containerName, String containerId, String masterUsername) {
        String effectiveUser = (masterUsername != null && !masterUsername.isBlank()) ? masterUsername : "postgres";
        String[] cmd = {
                "psql",
                "-v", "ON_ERROR_STOP=1",
                "-U", effectiveUser,
                "-d", "postgres",
                "-c", postgresIamRoleInitSql()
        };
        String lastOutput = "";
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                ContainerExecResult result = execInContainer(containerId, cmd, 5);
                lastOutput = result.output();
                if (result.exitCode() == 0) {
                    LOG.infov("Initialized PostgreSQL IAM role in RDS container {0}", containerName);
                    return;
                }
            } catch (Exception e) {
                lastOutput = e.getMessage();
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing PostgreSQL IAM role in " + containerName, e);
            }
        }
        throw new IllegalStateException("Timed out initializing PostgreSQL IAM role in " + containerName + ": " + lastOutput);
    }

    private ContainerExecResult execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = lifecycleManager.getDockerClient().execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Closeable callback = lifecycleManager.getDockerClient().execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getPayload() != null) {
                    try {
                        output.write(frame.getPayload());
                    } catch (IOException ignored) {
                    }
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
                return new ContainerExecResult(-1, "Timed out after " + timeoutSeconds + "s");
            }
            Long exitCode = lifecycleManager.getDockerClient().inspectExecCmd(execId).exec().getExitCodeLong();
            return new ContainerExecResult(
                    exitCode != null ? exitCode : -1,
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            callback.close();
        }
    }

    record ContainerExecResult(long exitCode, String output) {}

    public void removeVolume(String instanceId, String volumeId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeStorage(config, lifecycleManager, "rds", volumeId, instanceId);
        }
        // host-path mode: host directories are not removed automatically
    }

    private List<String> buildEnvVars(DatabaseEngine engine, String masterUsername,
                                      String masterPassword, String dbName) {
        String effectiveUser = (masterUsername != null && !masterUsername.isBlank()) ? masterUsername : "postgres";
        String effectiveDb = (dbName != null && !dbName.isBlank()) ? dbName : effectiveUser;

        List<String> envs = new ArrayList<>();
        switch (engine) {
            case POSTGRES -> {
                envs.add("POSTGRES_USER=" + effectiveUser);
                envs.add("POSTGRES_PASSWORD=" + masterPassword);
                envs.add("POSTGRES_DB=" + effectiveDb);
                envs.add("POSTGRES_HOST_AUTH_METHOD=md5");
            }
            case MYSQL -> {
                envs.add("MYSQL_ROOT_PASSWORD=" + masterPassword);
                if (!"root".equals(effectiveUser)) {
                    envs.add("MYSQL_USER=" + effectiveUser);
                    envs.add("MYSQL_PASSWORD=" + masterPassword);
                }
                envs.add("MYSQL_DATABASE=" + effectiveDb);
            }
            case MARIADB -> {
                envs.add("MARIADB_ROOT_PASSWORD=" + masterPassword);
                if (!"root".equals(effectiveUser)) {
                    envs.add("MARIADB_USER=" + effectiveUser);
                    envs.add("MARIADB_PASSWORD=" + masterPassword);
                }
                envs.add("MARIADB_DATABASE=" + effectiveDb);
            }
        }
        return envs;
    }

    private List<String> buildContainerCmd(DatabaseEngine engine) {
        // Configure MySQL to use mysql_native_password so the proxy can authenticate
        // without needing caching_sha2_password RSA key exchange
        return switch (engine) {
            case MYSQL -> List.of("--default-authentication-plugin=mysql_native_password");
            case POSTGRES, MARIADB -> List.of();
        };
    }
}
