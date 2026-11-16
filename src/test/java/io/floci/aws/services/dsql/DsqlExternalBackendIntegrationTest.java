package io.floci.aws.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aurora DSQL's data plane with every cluster on one external PostgreSQL server that wants
 * SCRAM-SHA-256 passwords: a client connects as DSQL's {@code admin} with an IAM token, and Floci
 * logs in to the cluster's own database as the cluster's backend admin.
 */
@QuarkusTest
@TestProfile(DsqlExternalBackendProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlExternalBackendIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @ConfigProperty(name = "floci.services.dsql.external.port")
    int serverPort;

    private DsqlClusterFixture cluster;

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @Test
    void adminConnectsThroughScramAuthenticatedBackend() throws Exception {
        try (Connection connection = cluster.connect("admin", cluster.adminToken());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user, current_database(), sys.iam_session_user()")) {
            assertTrue(row.next());
            assertEquals("d" + cluster.identifier() + "_admin", row.getString(1));
            assertEquals("dsql_" + cluster.identifier(), row.getString(2));
            assertEquals("arn:aws:iam::" + DsqlClusterFixture.ACCOUNT + ":root", row.getString(3));
        }
        assertEquals("0", onServer("SELECT count(*)::text FROM pg_hba_file_rules WHERE type = 'host'"
                + " AND auth_method <> 'scram-sha-256'"), "the server wants a SCRAM password of every TCP login");
        assertEquals("true", onServer("SELECT (rolpassword LIKE 'SCRAM-SHA-256$4096:%')::text FROM pg_authid"
                + " WHERE rolname = '" + backendAdmin() + "'"));
    }

    @Test
    void aBackendThatRefusesTheLoginIsDsqlsAccessDenied() throws Exception {
        // An expired password fails the SCRAM exchange whatever Floci sends.
        onServerExecute("ALTER ROLE \"" + backendAdmin() + "\" VALID UNTIL '2000-01-01'");
        try {
            PSQLException refused = assertThrows(PSQLException.class, () ->
                    cluster.connect("admin", cluster.adminToken()).close());

            assertEquals("08006", refused.getSQLState());
            assertEquals("unable to accept connection, access denied", refused.getServerErrorMessage().getMessage());
            assertTrue(refused.getServerErrorMessage().getDetail().startsWith("Session Id: "));
            assertFalse(refused.getMessage().contains(cluster.identifier()), refused.getMessage());
        } finally {
            onServerExecute("ALTER ROLE \"" + backendAdmin() + "\" VALID UNTIL 'infinity'");
        }
        try (Connection connection = cluster.connect("admin", cluster.adminToken())) {
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void sixteenParallelAdminLoginsAllSucceed() throws Exception {
        String verifier = onServer("SELECT rolpassword FROM pg_authid WHERE rolname = '" + backendAdmin() + "'");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> logins = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                logins.add(pool.submit(() -> {
                    try (Connection connection = cluster.connect("admin", cluster.adminToken());
                         Statement statement = connection.createStatement();
                         ResultSet row = statement.executeQuery("SELECT current_user")) {
                        assertTrue(row.next());
                        return row.getString(1);
                    }
                }));
            }
            for (Future<String> login : logins) {
                assertEquals(backendAdmin(), login.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(verifier, onServer("SELECT rolpassword FROM pg_authid WHERE rolname = '" + backendAdmin()
                + "'"), "the logins left the password as it was");
    }

    private String backendAdmin() {
        return "d" + cluster.identifier() + "_admin";
    }

    private String onServer(String sql) throws SQLException {
        try (Connection server = server(); Statement statement = server.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private void onServerExecute(String sql) throws SQLException {
        try (Connection server = server(); Statement statement = server.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection server() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + serverPort + "/postgres", "postgres",
                ExternalPostgresResource.PASSWORD);
    }
}
