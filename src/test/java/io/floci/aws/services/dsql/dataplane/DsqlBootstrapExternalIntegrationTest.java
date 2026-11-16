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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the bootstrap script rendered for the external backend against a throwaway PostgreSQL 16,
 * set up as a shared server holds one cluster: its own database, owner role and prefixed roles.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DsqlBootstrapExternalIntegrationTest {

    private static final String IMAGE = "postgres:16-alpine";
    private static final String DATABASE = "dsql_abc";
    private static final String OWNER = "dsqlowner_abc";
    private static final String ADMIN = "dabc_admin";
    private static final String PREFIX = "dabc_";
    private static final String ARN = "arn:aws:iam::123456789012:role/app";

    private DockerClient dockerClient;
    private String containerId;
    private int port;

    @BeforeAll
    void startServerAndBootstrapTwice() throws Exception {
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
                .withName("floci-dsql-bootstrap-" + UUID.randomUUID())
                .withEnv("POSTGRES_USER=postgres", "POSTGRES_HOST_AUTH_METHOD=trust")
                .withExposedPorts(exposed)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), exposed)))
                .exec().getId();
        dockerClient.startContainerCmd(containerId).exec();
        InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
        port = Integer.parseInt(inspected.getNetworkSettings().getPorts().getBindings().get(exposed)[0].getHostPortSpec());
        awaitReady();

        try (Connection connection = connect("postgres", "postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + OWNER + " NOLOGIN");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + OWNER);
            statement.execute("REVOKE CONNECT ON DATABASE " + DATABASE + " FROM PUBLIC");
        }
        String script = DsqlBootstrap.render(DATABASE, OWNER, ADMIN, PREFIX, true);
        for (int run = 0; run < 2; run++) {
            try (Connection connection = connect(DATABASE, "postgres"); Statement statement = connection.createStatement()) {
                statement.execute(script);
            }
        }
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
    void bootstrapRecordsTheAdminAndOwnerAndRolesTheirChildrenCreate() throws SQLException {
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "b WITH LOGIN CREATEROLE$$)");
        }
        try (Connection b = connect(DATABASE, PREFIX + "b"); Statement statement = b.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "c$$)");
        }

        Map<String, String> recorded = backendRoles();
        assertEquals(PREFIX + "b", recorded.get("b"));
        assertEquals(PREFIX + "c", recorded.get("c"));
        assertEquals(ADMIN, recorded.get("admin"));
        assertEquals(OWNER, recorded.get(OWNER));
        assertTrue(hasConnect(PREFIX + "c"), "a recorded role may connect to its cluster's database");

        List<String> visible = query(ADMIN, "SELECT rolname FROM sys.roles ORDER BY rolname");
        assertEquals(List.of("admin", "b", "c", OWNER), visible);

        try (Connection b = connect(DATABASE, PREFIX + "b"); Statement statement = b.createStatement()) {
            statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "c']::text[], false)");
        }
        assertFalse(backendRoles().containsKey("c"));
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "b']::text[], false)");
        }
        assertFalse(backendRoles().containsKey("b"));
    }

    @Test
    void iamMessagesAndMappingsNameRolesLogically() throws SQLException {
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            SQLException missing = assertThrows(SQLException.class, () ->
                    statement.execute("CALL sys.aws_iam_grant('" + PREFIX + "nope', '" + ARN + "')"));
            assertTrue(missing.getMessage().contains("role \"nope\" does not exist"), missing.getMessage());

            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "app$$)");
            statement.execute("CALL sys.aws_iam_grant('" + PREFIX + "app', '" + ARN + "')");
            SQLException mapped = assertThrows(SQLException.class, () ->
                    statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "app']::text[], false)"));
            assertTrue(mapped.getMessage().contains("role \"app\" cannot be dropped because some objects depend on it"),
                    mapped.getMessage());
            assertTrue(mapped.getMessage().contains("privileges for trust relationship of role app with arn " + ARN),
                    mapped.getMessage());
        }
        assertEquals(List.of("app|admin"),
                query(ADMIN, "SELECT pg_role_name || '|' || grantor_pg_role_name FROM sys.iam_pg_role_mappings"));
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.aws_iam_revoke('" + PREFIX + "app', '" + ARN + "')");
            statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "app']::text[], false)");
        }
    }

    private Map<String, String> backendRoles() throws SQLException {
        Map<String, String> roles = new TreeMap<>();
        try (Connection connection = connect(DATABASE, "postgres"); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT logical_name, backend_name FROM sys.backend_roles")) {
            while (rows.next()) {
                roles.put(rows.getString(1), rows.getString(2));
            }
        }
        return roles;
    }

    private boolean hasConnect(String role) throws SQLException {
        return query("postgres", "SELECT has_database_privilege('" + role + "', '" + DATABASE + "', 'CONNECT')::text")
                .equals(List.of("true"));
    }

    private List<String> query(String user, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(DATABASE, user); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private Connection connect(String database, String user) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database, user, "");
    }

    private void awaitReady() throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = connect("postgres", "postgres");
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw new SQLException("PostgreSQL did not start", last);
    }
}
