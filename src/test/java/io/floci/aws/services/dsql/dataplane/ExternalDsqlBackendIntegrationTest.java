package io.floci.aws.services.dsql.dataplane;

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
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.model.DsqlCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Creates, starts again and deletes one cluster's database and roles on a throwaway PostgreSQL 16
 * that wants passwords, as a shared server does, through the external backend alone.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalDsqlBackendIntegrationTest {

    private static final String IMAGE = "postgres:16-alpine";
    private static final String PASSWORD = "floci-test";
    private static final String ID = "abcdefghijklmnopqrstuvwxyz";
    private static final String DATABASE = "dsql_" + ID;
    private static final String ADMIN = "d" + ID + "_admin";
    private static final String OWNER = "dsqlowner_" + ID;

    private DockerClient dockerClient;
    private String containerId;
    private int port;

    @BeforeAll
    void startServer() throws Exception {
        try {
            DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(clientConfig.getDockerHost())
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofMinutes(5))
                    .build();
            dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
            dockerClient.pingCmd().exec();
            dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            assumeTrue(false, "Docker or " + IMAGE + " is not available: " + e.getMessage());
        }
        ExposedPort exposed = ExposedPort.tcp(5432);
        containerId = dockerClient.createContainerCmd(IMAGE)
                .withName("floci-dsql-external-" + UUID.randomUUID())
                .withEnv("POSTGRES_USER=postgres", "POSTGRES_PASSWORD=" + PASSWORD)
                .withExposedPorts(exposed)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), exposed)))
                .exec().getId();
        dockerClient.startContainerCmd(containerId).exec();
        InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
        port = Integer.parseInt(inspected.getNetworkSettings().getPorts().getBindings().get(exposed)[0].getHostPortSpec());
        awaitReady();
    }

    @AfterAll
    void removeServer() throws IOException {
        if (containerId != null) {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            } catch (DockerException e) {
                // The container is a throwaway; Docker reaps what it could not remove here.
                System.err.println("Could not remove " + containerId + ": " + e.getMessage());
            }
        }
        if (dockerClient != null) {
            dockerClient.close();
        }
    }

    @Test
    void aClusterIsCreatedStartedAgainAndDeletedWithItsRoles() throws Exception {
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier(ID);

        DsqlDatabases.Backend first = backend.start(cluster);
        String secret = cluster.getBackendSecret();
        DsqlDatabases.Backend again = backend.start(cluster);

        assertEquals(secret, cluster.getBackendSecret());
        assertEquals(DATABASE, again.database());
        assertEquals("postgres", again.superuser());
        assertEquals(PASSWORD, again.superuserPassword());
        assertEquals(first.credentials().passwordFor(ADMIN), again.credentials().passwordFor(ADMIN));
        try (Connection admin = DriverManager.getConnection(again.jdbcUrl(), ADMIN,
                again.credentials().passwordFor(ADMIN)); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE d" + ID + "_app$$)");
        }
        assertEquals("false", single("postgres", "SELECT rolcreatedb::text FROM pg_roles WHERE rolname = '"
                + ADMIN + "'"));
        assertEquals("false", single("postgres", "SELECT has_database_privilege('public', '" + DATABASE
                + "', 'CONNECT')::text"));

        List<String> roles = backend.rolesToDrop(cluster);
        assertEquals(List.of("d" + ID + "_app", ADMIN, OWNER), roles);
        cluster.setPendingBackendRoles(roles);
        try (Connection open = DriverManager.getConnection(again.jdbcUrl(), ADMIN,
                again.credentials().passwordFor(ADMIN))) {
            // A session still open on the database does not keep it from being dropped.
            backend.stop(cluster, true);
            assertFalse(open.isValid(1), "the session was ended");
        }

        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = '"
                + DATABASE + "'"));
        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname LIKE 'd" + ID
                + "%' OR rolname = '" + OWNER + "'"));
        assertEquals(roles, backend.rolesToDrop(cluster), "with the database gone, the saved roles are dropped");
        backend.stop(cluster, true);
    }

    @Test
    void aFailedStartLeavesARoleItDidNotCreate() throws Exception {
        String id = "zyxwvutsrqponmlkjihgfedcba";
        String owner = "dsqlowner_" + id;
        try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
            statement.execute("CREATE ROLE " + owner + " NOLOGIN");
            // A database already there, whose sys.backend_roles has another shape, fails the bootstrap.
            statement.execute("CREATE DATABASE dsql_" + id);
        }
        try (Connection database = connect("dsql_" + id); Statement statement = database.createStatement()) {
            statement.execute("CREATE SCHEMA sys");
            statement.execute("CREATE TABLE sys.backend_roles (unexpected int)");
        }
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier(id);

        boolean failed = false;
        try {
            new ExternalDsqlBackend(config()).start(cluster);
        } catch (SQLException e) {
            failed = true;
        }

        assertTrue(failed, "the bootstrap fails on a sys.backend_roles of another shape");
        assertEquals("1", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname = '" + owner + "'"));
        assertEquals("1", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_" + id
                + "'"));
        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname = 'd" + id
                + "_admin'"), "the admin this attempt created is dropped again");
        assertFalse(cluster.getBackendSecret() == null);
    }

    @Test
    void passwordsAreStoredAsScramVerifiersThatALoginSetsAgain() throws Exception {
        String id = "mnopqrstuvwxyzabcdefghijkl";
        String admin = "d" + id + "_admin";
        String owner = "dsqlowner_" + id;
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier(id);
        DsqlDatabases.Backend started = backend.start(cluster);
        try {
            String verifier = started.credentials().verifierFor(admin);
            String password = started.credentials().passwordFor(admin);
            assertFalse(DsqlEndpoint.setLoginPassword(started, admin), "a password already set is left as it is");
            assertTrue(verifier.startsWith("SCRAM-SHA-256$4096:"), verifier);
            assertEquals(verifier, single("postgres", "SELECT rolpassword FROM pg_authid WHERE rolname = '"
                    + admin + "'"), "the server keeps the verifier Floci sent, not one of its own");
            try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
                statement.execute("ALTER ROLE " + admin + " PASSWORD 'changed-behind-flocis-back'");
            }

            assertTrue(DsqlEndpoint.setLoginPassword(started, admin));
            assertFalse(DsqlEndpoint.setLoginPassword(started, admin), "once set, it is not set again");

            assertEquals(verifier, single("postgres", "SELECT rolpassword FROM pg_authid WHERE rolname = '"
                    + admin + "'"));
            try (Connection login = DriverManager.getConnection(started.jdbcUrl(), admin, password);
                 Statement statement = login.createStatement();
                 ResultSet row = statement.executeQuery("SELECT current_user")) {
                assertTrue(row.next());
                assertEquals(admin, row.getString(1));
            }
            assertFalse(DsqlEndpoint.setLoginPassword(started, "d" + id + "_nosuch"), "a missing role is left alone");
            assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname = 'd" + id
                    + "_nosuch'"));
            assertFalse(DsqlEndpoint.setLoginPassword(started, owner), "a role that cannot log in is left alone");
            assertEquals("true", single("postgres", "SELECT (rolpassword IS NULL)::text FROM pg_authid"
                    + " WHERE rolname = '" + owner + "'"));
        } finally {
            cluster.setPendingBackendRoles(backend.rolesToDrop(cluster));
            backend.stop(cluster, true);
        }
    }

    private EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dsql().external().host()).thenReturn(Optional.of("127.0.0.1"));
        when(config.services().dsql().external().port()).thenReturn(port);
        when(config.services().dsql().external().database()).thenReturn("postgres");
        when(config.services().dsql().external().username()).thenReturn("postgres");
        when(config.services().dsql().external().password()).thenReturn(Optional.of(PASSWORD));
        return config;
    }

    private String single(String database, String sql) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database, "postgres",
                PASSWORD);
    }

    private void awaitReady() throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = connect("postgres"); Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw new SQLException("PostgreSQL did not start within 60 s", last);
    }
}
