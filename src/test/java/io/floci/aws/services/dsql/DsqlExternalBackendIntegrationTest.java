package io.floci.aws.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.floci.aws.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
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

    @Inject
    DsqlService service;

    @Inject
    IamService iamService;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @ConfigProperty(name = "floci.services.dsql.external.port")
    int serverPort;

    private static final String ROLE_ARN = "arn:aws:iam::" + DsqlClusterFixture.ACCOUNT + ":role/service/app-role";

    private DsqlClusterFixture cluster;
    private final List<DsqlClusterFixture> created = new ArrayList<>();

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @AfterEach
    void deleteCreatedClusters() {
        for (DsqlClusterFixture each : created) {
            each.delete();
        }
        created.clear();
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

    @Test
    void twoClustersAreIsolated() throws Exception {
        DsqlClusterFixture a = newCluster();
        DsqlClusterFixture b = newCluster();
        adminExecute(a, "CREATE TABLE a_only(x int)", "CREATE ROLE app");

        adminExecute(b, "CREATE ROLE app");
        assertEquals(List.of("true"), adminQuery(b, "SELECT (to_regclass('a_only') IS NULL)::text"));

        assertEquals("1", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname = 'd" + a.identifier()
                + "_app'"));
        assertEquals("1", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname = 'd" + b.identifier()
                + "_app'"));
        // A password of the test's own, so the refusal below is the database's, not the login's.
        String adminA = backendAdmin(a);
        onServerExecute("ALTER ROLE \"" + adminA + "\" PASSWORD 'probe-password'");
        try (Connection own = DriverManager.getConnection(serverUrl("dsql_" + a.identifier()), adminA,
                "probe-password")) {
            assertTrue(own.isValid(5));
        }
        PSQLException refused = assertThrows(PSQLException.class, () -> DriverManager.getConnection(
                serverUrl("dsql_" + b.identifier()), adminA, "probe-password").close());
        assertTrue(List.of("42501", "28000").contains(refused.getSQLState()), refused.getSQLState() + ": "
                + refused.getMessage());

        // Floci sets the password it derives again on the next login.
        try (Connection connection = a.connect("admin", a.adminToken())) {
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void sysRolesShowsLogicalNames() throws Exception {
        DsqlClusterFixture a = newCluster();
        DsqlClusterFixture b = newCluster();
        adminExecute(a, "CREATE ROLE app");
        adminExecute(b, "CREATE ROLE b_only");

        assertEquals(List.of("admin", "app"), adminQuery(a, "SELECT rolname FROM sys.roles ORDER BY rolname"),
                "logical names, no d<id>_ names, no owner and nothing of the other cluster's");
        assertEquals(List.of("admin", "b_only"), adminQuery(b, "SELECT rolname FROM sys.roles ORDER BY rolname"));

        adminExecute(a, "AWS IAM GRANT app TO '" + ROLE_ARN + "'");
        assertEquals(List.of("app|admin"), adminQuery(a,
                "SELECT pg_role_name || '|' || grantor_pg_role_name FROM sys.iam_pg_role_mappings"));
    }

    @Test
    void quotedRoleNameKeepsItsCase() throws Exception {
        String prefix = "d" + cluster.identifier() + "_";
        adminExecute(cluster, "CREATE ROLE \"Reader\"");
        try {
            assertEquals("1", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname = '" + prefix + "Reader'"));
            assertEquals("0", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname = '" + prefix + "reader'"));
            assertEquals(List.of("Reader"), adminQuery(cluster,
                    "SELECT rolname FROM sys.roles WHERE lower(rolname) = 'reader'"));
        } finally {
            adminExecute(cluster, "DROP ROLE \"Reader\"");
        }
        assertEquals("0", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname = '" + prefix + "Reader'"));
    }

    @Test
    void overlongRoleNameIsRefused() throws Exception {
        String longest = "r".repeat(35);
        String overlong = "r".repeat(36);
        String prefix = "d" + cluster.identifier() + "_";
        String before = serverRolesOf(cluster);

        try (Connection admin = cluster.connect("admin", cluster.adminToken());
             Statement statement = admin.createStatement()) {
            PSQLException refused = assertThrows(PSQLException.class, () ->
                    statement.execute("CREATE ROLE " + overlong));
            assertEquals("42602", refused.getSQLState());
            assertEquals("role name \"" + overlong + "\" is too long for Floci's external backend (max 35"
                    + " characters)", refused.getServerErrorMessage().getMessage());
        }
        assertEquals(before, serverRolesOf(cluster), "nothing was created");

        adminExecute(cluster, "CREATE ROLE " + longest);
        try {
            assertEquals("63", onServer("SELECT length(rolname)::text FROM pg_roles WHERE rolname = '" + prefix
                    + longest + "'"));
        } finally {
            adminExecute(cluster, "DROP ROLE " + longest);
        }
    }

    @Test
    void delegatedRolesAreRecordedAndDropped() throws Exception {
        DsqlClusterFixture c = newCluster();
        String prefix = "d" + c.identifier() + "_";
        String accessKeyId = "ASIA" + Long.toString(System.nanoTime(), 36).toUpperCase();
        String secret = "delegate+session/secret=key";
        String sessionToken = "IQoJb3JpZ2luX2VjEJr+delegate/role+session==";
        iamService.registerLambdaExecutionRoleSession(DsqlClusterFixture.ACCOUNT, accessKeyId, secret,
                sessionToken, ROLE_ARN);
        try {
            adminExecute(c, "CREATE ROLE b WITH LOGIN CREATEROLE", "AWS IAM GRANT b TO '" + ROLE_ARN + "'");
            try (Connection b = c.connect("b", c.token("DbConnect", accessKeyId, secret, Instant.now(), 900,
                    sessionToken)); Statement statement = b.createStatement()) {
                statement.execute("CREATE ROLE c");
            }
        } finally {
            iamService.unregisterSession(DsqlClusterFixture.ACCOUNT, accessKeyId);
        }
        assertEquals(List.of("admin", "b", "c"), adminQuery(c, "SELECT rolname FROM sys.roles ORDER BY rolname"));
        assertEquals("3", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname IN ('" + prefix + "admin', '"
                + prefix + "b', '" + prefix + "c')"));

        deleteNow(c);

        assertEquals("0", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname IN ('" + prefix + "admin', '"
                + prefix + "b', '" + prefix + "c', 'dsqlowner_" + c.identifier() + "')"));
        assertEquals("0", onServer("SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_" + c.identifier()
                + "'"));
    }

    @Test
    void deletionKeepsUnrelatedDatabasesAndRoles() throws Exception {
        DsqlClusterFixture c = newCluster();
        String lookalike = "d" + c.identifier() + "_lookalike";
        onServerExecute("CREATE DATABASE unrelated");
        try {
            onServerExecute("CREATE ROLE unrelated_role");
            onServerExecute("CREATE ROLE \"" + lookalike + "\"");

            deleteNow(c);

            assertEquals("1", onServer("SELECT count(*)::text FROM pg_database WHERE datname = 'unrelated'"));
            assertEquals("2", onServer("SELECT count(*)::text FROM pg_roles WHERE rolname IN ('unrelated_role', '"
                    + lookalike + "')"));
            assertEquals("0", onServer("SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_"
                    + c.identifier() + "'"), "the cluster's own database is gone");
        } finally {
            onServerExecute("DROP DATABASE IF EXISTS unrelated");
            onServerExecute("DROP ROLE IF EXISTS unrelated_role");
            onServerExecute("DROP ROLE IF EXISTS \"" + lookalike + "\"");
        }
    }

    @Test
    void restartKeepsData() throws Exception {
        DsqlClusterFixture c = newCluster();
        String admin = backendAdmin(c);
        String database = "dsql_" + c.identifier();
        adminExecute(c, "CREATE TABLE kept(id int PRIMARY KEY, note text)");
        adminExecute(c, "INSERT INTO kept VALUES (1, 'survives')");
        String verifier = onServer("SELECT rolpassword FROM pg_authid WHERE rolname = '" + admin + "'");
        // Only a start grants it again, which tells the test when the start below has run.
        onServerExecute("REVOKE CONNECT ON DATABASE \"" + database + "\" FROM \"" + admin + "\"");

        // What a start of Floci does with the clusters in its storage.
        service.onStart(null);

        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofMillis(200)).until(() ->
                "true".equals(onServer("SELECT has_database_privilege('" + admin + "', '" + database
                        + "', 'CONNECT')::text")));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() ->
                adminQuery(c, "SELECT note FROM kept WHERE id = 1").equals(List.of("survives")));
        assertEquals(verifier, onServer("SELECT rolpassword FROM pg_authid WHERE rolname = '" + admin + "'"),
                "the cluster's saved secret gives its admin the same password");
        assertEquals("ACTIVE", c.status());
    }

    private String backendAdmin() {
        return "d" + cluster.identifier() + "_admin";
    }

    private static String backendAdmin(DsqlClusterFixture fixture) {
        return "d" + fixture.identifier() + "_admin";
    }

    private DsqlClusterFixture newCluster() {
        DsqlClusterFixture fixture = new DsqlClusterFixture(httpPort, endpoint);
        created.add(fixture);
        fixture.create(dockerClient);
        return fixture;
    }

    /** Deletes the cluster through the API and waits until it is gone. */
    private void deleteNow(DsqlClusterFixture fixture) {
        fixture.delete();
        created.remove(fixture);
    }

    private static void adminExecute(DsqlClusterFixture fixture, String... statements) throws Exception {
        try (Connection admin = fixture.connect("admin", fixture.adminToken());
             Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> adminQuery(DsqlClusterFixture fixture, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection admin = fixture.connect("admin", fixture.adminToken());
             Statement statement = admin.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /** The backend roles on the server that carry the cluster's prefix, in order. */
    private String serverRolesOf(DsqlClusterFixture fixture) throws SQLException {
        return onServer("SELECT coalesce(string_agg(rolname, ',' ORDER BY rolname), '') FROM pg_roles"
                + " WHERE starts_with(rolname, 'd" + fixture.identifier() + "_')");
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
        return DriverManager.getConnection(serverUrl("postgres"), "postgres", ExternalPostgresResource.PASSWORD);
    }

    private String serverUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + serverPort + "/" + database;
    }
}
