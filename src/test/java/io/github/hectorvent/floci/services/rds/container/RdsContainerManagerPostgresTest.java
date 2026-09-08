package io.github.hectorvent.floci.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round-trip test for PostgreSQL snapshot dump and restore using real Docker containers.
 * Verifies that a dump taken from one container can be restored into a fresh container
 * with the same master user, which previously failed due to "current user cannot be dropped".
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RdsContainerManagerPostgresTest {

    private static final String IMAGE = "postgres:16-alpine";
    private static final String MASTER_USER = "admin";
    private static final String MASTER_PASSWORD = "secret";
    private static final String DB_NAME = "appdb";
    private static final int PG_PORT = 5432;

    private DockerClient dockerClient;
    private String sourceContainerId;
    private String targetContainerId;

    @BeforeAll
    void setUp() {
        try {
            DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig
                    .createDefaultConfigBuilder()
                    .build();
            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(clientConfig.getDockerHost())
                    .maxConnections(10)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofMinutes(5))
                    .build();
            dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }

        try {
            dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            assumeTrue(false, "Could not pull " + IMAGE + ": " + e.getMessage());
        }
    }

    @AfterAll
    void tearDown() {
        removeContainer(sourceContainerId);
        removeContainer(targetContainerId);
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Test
    void snapshotRoundTripPreservesDataWithNonDefaultUser() throws Exception {
        // 1. Start source container with non-default master user
        sourceContainerId = startPostgresContainer("floci-test-pg-source");
        waitForPostgresReady(sourceContainerId, MASTER_USER, DB_NAME, 60);

        // 2. Create a probe table with data in the application database
        execPsql(sourceContainerId, MASTER_USER, DB_NAME,
                "CREATE TABLE probe (id serial PRIMARY KEY, value text);"
                        + "INSERT INTO probe (value) VALUES ('round-trip-test');");

        // 3. Dump using the production code path
        RdsContainerManager manager = createManager();
        String dump = manager.createPostgresSnapshot(sourceContainerId, MASTER_USER);

        assertTrue(dump.contains("CREATE ROLE " + MASTER_USER),
                "Dump should contain CREATE ROLE for the master user");
        assertTrue(dump.contains(DB_NAME),
                "Dump should reference the application database");

        // 4. Start a fresh target container with the same master user
        targetContainerId = startPostgresContainer("floci-test-pg-target");
        waitForPostgresReady(targetContainerId, MASTER_USER, DB_NAME, 60);

        // 5. Restore using the production code path
        manager.restorePostgresSnapshot(targetContainerId, MASTER_USER, dump);

        // 6. Verify the probe row exists in the target
        String result = queryPsql(targetContainerId, MASTER_USER, DB_NAME,
                "SELECT value FROM probe WHERE id = 1");
        assertEquals("round-trip-test", result.trim(),
                "Restored database should contain the probe row");
    }

    private String startPostgresContainer(String name) {
        removeContainerByName(name);

        ExposedPort pgPort = ExposedPort.tcp(PG_PORT);
        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE)
                .withName(name)
                .withExposedPorts(pgPort)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(new Ports(pgPort, Ports.Binding.empty())))
                .withEnv(
                        "POSTGRES_USER=" + MASTER_USER,
                        "POSTGRES_PASSWORD=" + MASTER_PASSWORD,
                        "POSTGRES_DB=" + DB_NAME,
                        "POSTGRES_HOST_AUTH_METHOD=md5"
                )
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    private void waitForPostgresReady(String containerId, String user, String dbName, int maxSeconds) {
        String[] cmd = {"psql", "-U", user, "-d", dbName, "-c", "SELECT 1"};
        for (int i = 0; i < maxSeconds; i++) {
            try {
                ExecResult result = exec(containerId, cmd, 5);
                if (result.exitCode() == 0) {
                    return;
                }
            } catch (Exception ignored) {
                // not ready yet
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for PostgreSQL", e);
            }
        }
        throw new RuntimeException("PostgreSQL did not become ready within " + maxSeconds + "s");
    }

    private void execPsql(String containerId, String user, String db, String sql) throws Exception {
        String[] cmd = {"psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-c", sql};
        ExecResult result = exec(containerId, cmd, 30);
        if (result.exitCode() != 0) {
            throw new RuntimeException("psql exec failed (exit " + result.exitCode() + "): "
                    + result.stderr() + " | " + result.stdout());
        }
    }

    private String queryPsql(String containerId, String user, String db, String sql) throws Exception {
        String[] cmd = {"psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-tAc", sql};
        ExecResult result = exec(containerId, cmd, 30);
        if (result.exitCode() != 0) {
            throw new RuntimeException("psql query failed (exit " + result.exitCode() + "): "
                    + result.stderr() + " | " + result.stdout());
        }
        return result.stdout();
    }

    private ExecResult exec(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Closeable callback = dockerClient.execStartCmd(execId)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getPayload() != null) {
                            try {
                                if (frame.getStreamType() == StreamType.STDOUT) {
                                    stdout.write(frame.getPayload());
                                } else if (frame.getStreamType() == StreamType.STDERR) {
                                    stderr.write(frame.getPayload());
                                }
                            } catch (IOException ignored) {
                                // best effort
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        latch.countDown();
                    }
                });

        try {
            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                return new ExecResult(-1, stdout.toString(StandardCharsets.UTF_8),
                        "Timed out after " + timeoutSeconds + "s");
            }
            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(
                    exitCode != null ? exitCode : -1,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            callback.close();
        }
    }

    private RdsContainerManager createManager() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        return new RdsContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(EmulatorConfig.class),
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
    }

    private void removeContainer(String containerId) {
        if (containerId == null || dockerClient == null) {
            return;
        }
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (DockerException ignored) {
            // already stopped or gone
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (DockerException ignored) {
            // already removed
        }
    }

    private void removeContainerByName(String name) {
        try {
            List<com.github.dockerjava.api.model.Container> containers = dockerClient
                    .listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of(name))
                    .exec();
            for (com.github.dockerjava.api.model.Container c : containers) {
                removeContainer(c.getId());
            }
        } catch (DockerException ignored) {
            // best effort
        }
    }

    record ExecResult(long exitCode, String stdout, String stderr) {}
}
