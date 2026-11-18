package io.floci.aws.services.dsql;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.floci.aws.services.dsql.dataplane.DsqlDatabases;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestDockerClients;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private final String password;

    private ExternalPostgresServer(DockerClient dockerClient, String containerId, int port, String password) {
        this.dockerClient = dockerClient;
        this.containerId = containerId;
        this.port = port;
        this.password = password;
    }

    /**
     * The superuser {@code postgres} of a test server on 127.0.0.1, for the SQL a test runs on it
     * directly, outside Floci.
     */
    public record Superuser(int port, String password) {

        public String url(String database) {
            return jdbcUrl(port, database);
        }

        public Connection connect(String database) throws SQLException {
            return DriverManager.getConnection(url(database), "postgres", password);
        }

        /** The first column of the one row {@code sql} returns in {@code database}. */
        public String single(String database, String sql) throws SQLException {
            try (Connection connection = connect(database); Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery(sql)) {
                assertTrue(row.next(), sql);
                return row.getString(1);
            }
        }

        /** The first column of the one row {@code sql} returns in the database {@code postgres}. */
        public String single(String sql) throws SQLException {
            return single("postgres", sql);
        }

        /** Runs {@code sql} in the database {@code postgres}. */
        public void execute(String sql) throws SQLException {
            try (Connection connection = connect("postgres"); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
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
     * {@code password} (empty where the server trusts its connections), through the readiness loop
     * the DSQL backends wait for a server with.
     */
    public static ExternalPostgresServer start(String name, String password, String... env)
            throws DockerUnavailableException, SQLException, InterruptedException {
        DockerClient dockerClient = TestDockerClients.local();
        String containerId = null;
        try {
            if (!DockerAvailability.answers(dockerClient)) {
                throw new DockerUnavailableException("Docker does not answer", null);
            }
            try {
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
            DsqlDatabases.awaitConnection(() -> DriverManager.getConnection(jdbcUrl(port, "postgres"), "postgres",
                    password), READY_TIMEOUT, DsqlDatabases.READY_INTERVAL, false, "The test's PostgreSQL").close();
            return new ExternalPostgresServer(dockerClient, containerId, port, password);
        } catch (DockerUnavailableException | SQLException | InterruptedException | RuntimeException e) {
            close(dockerClient, containerId, e);
            throw e;
        }
    }

    public int port() {
        return port;
    }

    public String jdbcUrl(String database) {
        return jdbcUrl(port, database);
    }

    public Superuser superuser() {
        return new Superuser(port, password);
    }

    private static String jdbcUrl(int port, String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    @Override
    public void close() {
        close(dockerClient, containerId, null);
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
}
