package io.floci.aws.services.dsql;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway PostgreSQL 16 that wants SCRAM-SHA-256 passwords, as a shared server does, started
 * before Floci boots and set as the external DSQL backend. Without Docker the overrides point at a
 * port where nothing listens, and {@link DsqlClusterFixture#create} skips the test class.
 */
public final class ExternalPostgresResource implements QuarkusTestResourceLifecycleManager {

    public static final String PASSWORD = "floci-test";
    /** A resource argument: the password Floci is configured with, when it should not be the server's. */
    public static final String FLOCI_PASSWORD = "flociPassword";
    private static final String IMAGE = "postgres:16-alpine";

    private DockerClient dockerClient;
    private String containerId;
    private String flociPassword = PASSWORD;

    @Override
    public void init(Map<String, String> initArgs) {
        flociPassword = initArgs.getOrDefault(FLOCI_PASSWORD, PASSWORD);
    }

    @Override
    public Map<String, String> start() {
        Map<String, String> overrides = new HashMap<>(new DsqlDatabasesProfile().getConfigOverrides());
        overrides.put("floci.services.dsql.backend", "external");
        overrides.put("floci.services.dsql.external.host", "127.0.0.1");
        overrides.put("floci.services.dsql.external.password", flociPassword);
        int port;
        try {
            port = startServer();
        } catch (Exception e) {
            System.err.println("No external PostgreSQL for the DSQL tests: " + e.getMessage());
            // Nothing listens on port 1; the tests skip before they create a cluster.
            port = 1;
        }
        overrides.put("floci.services.dsql.external.port", String.valueOf(port));
        return overrides;
    }

    private int startServer() throws Exception {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofMinutes(5))
                .build();
        dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
        dockerClient.pingCmd().exec();
        dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(120, TimeUnit.SECONDS);
        ExposedPort exposed = ExposedPort.tcp(5432);
        containerId = dockerClient.createContainerCmd(IMAGE)
                .withName("floci-dsql-external-" + UUID.randomUUID())
                .withEnv("POSTGRES_USER=postgres", "POSTGRES_PASSWORD=" + PASSWORD,
                        "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256")
                .withExposedPorts(exposed)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), exposed)))
                .exec().getId();
        dockerClient.startContainerCmd(containerId).exec();
        InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
        int port = Integer.parseInt(
                inspected.getNetworkSettings().getPorts().getBindings().get(exposed)[0].getHostPortSpec());
        awaitReady(port);
        return port;
    }

    private static void awaitReady(int port) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port
                    + "/postgres", "postgres", PASSWORD); Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw new SQLException("PostgreSQL did not start within 60 s", last);
    }

    @Override
    public void stop() {
        if (containerId != null) {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            } catch (DockerException e) {
                // The container is a throwaway; Docker reaps what it could not remove here.
                System.err.println("Could not remove " + containerId + ": " + e.getMessage());
            }
        }
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (IOException e) {
                System.err.println("Could not close the Docker client: " + e.getMessage());
            }
        }
    }
}
