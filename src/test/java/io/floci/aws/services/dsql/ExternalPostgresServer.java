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

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway PostgreSQL 16 in Docker for the external DSQL backend's tests, bound to a free port
 * on 127.0.0.1, whose superuser is {@code postgres}. {@link #close()} removes the container and
 * always closes the Docker client.
 */
public final class ExternalPostgresServer implements AutoCloseable {

    public static final String IMAGE = "postgres:16-alpine";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);

    private final DockerClient dockerClient;
    private final String containerId;
    private final int port;

    private ExternalPostgresServer(DockerClient dockerClient, String containerId, int port) {
        this.dockerClient = dockerClient;
        this.containerId = containerId;
        this.port = port;
    }

    /** Docker, or the image, is not there; a test skips rather than fails. Its cause says why. */
    public static final class DockerUnavailableException extends Exception {
        DockerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Starts a container named {@code name} plus a random suffix, with {@code env} besides
     * {@code POSTGRES_USER=postgres}, and waits until {@code postgres} logs in with
     * {@code password} (empty where the server trusts its connections). A refused login fails at once.
     */
    public static ExternalPostgresServer start(String name, String password, String... env)
            throws DockerUnavailableException, SQLException, InterruptedException {
        DockerClient dockerClient = client();
        String containerId = null;
        try {
            try {
                dockerClient.pingCmd().exec();
                dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(120, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                throw new DockerUnavailableException("Docker or " + IMAGE + " is not available: " + e.getMessage(), e);
            }
            List<String> environment = new ArrayList<>(List.of("POSTGRES_USER=postgres"));
            environment.addAll(List.of(env));
            ExposedPort exposed = ExposedPort.tcp(5432);
            containerId = dockerClient.createContainerCmd(IMAGE)
                    .withName(name + "-" + UUID.randomUUID())
                    .withEnv(environment)
                    .withExposedPorts(exposed)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), exposed)))
                    .exec().getId();
            dockerClient.startContainerCmd(containerId).exec();
            InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
            int port = Integer.parseInt(
                    inspected.getNetworkSettings().getPorts().getBindings().get(exposed)[0].getHostPortSpec());
            awaitReady(port, password);
            return new ExternalPostgresServer(dockerClient, containerId, port);
        } catch (DockerUnavailableException | SQLException | InterruptedException | RuntimeException e) {
            close(dockerClient, containerId, e);
            throw e;
        }
    }

    public int port() {
        return port;
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    @Override
    public void close() {
        close(dockerClient, containerId, null);
    }

    private static DockerClient client() {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofMinutes(5))
                .build();
        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    /** Removes the container, if any, then closes the client whatever the removal did. */
    private static void close(DockerClient dockerClient, String containerId, Exception failure) {
        try {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            }
        } catch (DockerException e) {
            // The container is a throwaway; Docker reaps what it could not remove here.
            report(failure, "Could not remove " + containerId, e);
        } finally {
            try {
                dockerClient.close();
            } catch (IOException e) {
                report(failure, "Could not close the Docker client", e);
            }
        }
    }

    private static void report(Exception failure, String what, Exception e) {
        if (failure != null) {
            failure.addSuppressed(e);
        } else {
            System.err.println(what + ": " + e.getMessage());
        }
    }

    private static void awaitReady(int port, String password) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port
                    + "/postgres", "postgres", password); Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("28")) {
                    // A refused login: waiting would not change the password.
                    throw e;
                }
                last = e;
                Thread.sleep(250);
            }
        }
        throw new SQLException("PostgreSQL did not start within " + READY_TIMEOUT.toSeconds() + " s", last);
    }
}
