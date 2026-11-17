package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.DsqlPrincipals;
import io.floci.aws.services.dsql.ExternalPostgresServer;
import io.floci.aws.services.dsql.model.DsqlCluster;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ScpProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private static final String PASSWORD = "floci-test";
    private static final String ID = "abcdefghijklmnopqrstuvwxyz";
    private static final String DATABASE = "dsql_" + ID;
    private static final String ADMIN = "d" + ID + "_admin";
    private static final String OWNER = "dsqlowner_" + ID;
    private static final String CONNECT = "dsqlconnect_" + ID;

    private ExternalPostgresServer server;
    private int port;

    @BeforeAll
    void startServer() throws Exception {
        try {
            server = ExternalPostgresServer.start("floci-dsql-external", PASSWORD, "POSTGRES_PASSWORD=" + PASSWORD);
        } catch (ExternalPostgresServer.DockerUnavailableException e) {
            throw new TestAbortedException(e.getMessage(), e);
        }
        port = server.port();
    }

    @AfterAll
    void removeServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aClusterIsCreatedStartedAgainAndDeletedWithItsRoles() throws Exception {
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(ID);

        DsqlDatabases.Backend first = backend.start(cluster);
        String secret = cluster.getBackendSecret();
        DsqlDatabases.Backend again = backend.start(cluster);

        assertEquals(secret, cluster.getBackendSecret());
        String marker = "floci:dsql:" + cluster.getArn();
        assertEquals(marker, single("postgres", "SELECT shobj_description(oid, 'pg_database') FROM pg_database"
                + " WHERE datname = '" + DATABASE + "'"), "the database carries the cluster's marker");
        assertEquals(List.of(marker, marker, marker), List.of(
                single("postgres", "SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = '"
                        + ADMIN + "'"),
                single("postgres", "SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = '"
                        + CONNECT + "'"),
                single("postgres", "SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = '"
                        + OWNER + "'")), "so do its admin, connect role and owner, which the start again reused");
        assertEquals(DATABASE, again.database());
        assertTrue(again.credentials().scramOnly(), "logins answer SCRAM alone");
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
        assertEquals("true", single("postgres", "SELECT (pg_has_role('d" + ID + "_app', '" + CONNECT
                + "', 'USAGE') AND has_database_privilege('d" + ID + "_app', '" + DATABASE + "', 'CONNECT'))::text"),
                "a role the admin created connects as a member of the connect role");

        List<String> roles = backend.rolesToDrop(cluster);
        assertEquals(List.of("d" + ID + "_app", ADMIN, CONNECT, OWNER), roles);
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
                + "%' OR rolname IN ('" + OWNER + "', '" + CONNECT + "')"));
        assertEquals(roles, backend.rolesToDrop(cluster), "with the database gone, the saved roles are dropped");
        backend.stop(cluster, true);
    }

    @Test
    void aFailedStartLeavesARoleItDidNotCreate() throws Exception {
        String id = "zyxwvutsrqponmlkjihgfedcba";
        String owner = "dsqlowner_" + id;
        DsqlCluster cluster = cluster(id);
        try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
            // An owner and a database an earlier start created for this cluster, so they carry its marker.
            statement.execute("CREATE ROLE " + owner + " NOLOGIN");
            statement.execute("COMMENT ON ROLE " + owner + " IS '" + ExternalDsqlBackend.marker(cluster) + "'");
            // A database whose sys.backend_roles has another shape fails the bootstrap.
            statement.execute("CREATE DATABASE dsql_" + id);
            statement.execute("COMMENT ON DATABASE dsql_" + id + " IS '" + ExternalDsqlBackend.marker(cluster) + "'");
        }
        try (Connection database = connect("dsql_" + id); Statement statement = database.createStatement()) {
            statement.execute("CREATE SCHEMA sys");
            statement.execute("CREATE TABLE sys.backend_roles (unexpected int)");
        }

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
        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('d" + id
                + "_admin', 'dsqlconnect_" + id + "')"), "the admin and connect role this attempt created are dropped again");
        assertFalse(cluster.getBackendSecret() == null);
    }

    @Test
    void passwordsAreStoredAsScramVerifiersThatALoginSetsAgain() throws Exception {
        String id = "mnopqrstuvwxyzabcdefghijkl";
        String admin = "d" + id + "_admin";
        String owner = "dsqlowner_" + id;
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(id);
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

    @Test
    void deletionFinishesFromPersistedListWhenDatabaseIsGone() throws Exception {
        String id = "ghijklmnopqrstuvwxyzabcdef";
        String admin = "d" + id + "_admin";
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(id);
        DsqlDatabases.Backend started = backend.start(cluster);
        try (Connection login = DriverManager.getConnection(started.jdbcUrl(), admin,
                started.credentials().passwordFor(admin)); Statement statement = login.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE d" + id + "_app$$)");
        }
        List<String> roles = backend.rolesToDrop(cluster);
        // What DsqlDataPlane persists with the cluster before it drops anything.
        cluster.setPendingBackendRoles(roles);
        try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
            // A deletion interrupted after its DROP DATABASE.
            statement.execute("DROP DATABASE dsql_" + id + " WITH (FORCE)");
        }
        assertEquals("4", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname LIKE 'd" + id
                + "%' OR rolname IN ('dsqlowner_" + id + "', 'dsqlconnect_" + id + "')"));

        assertEquals(roles, backend.rolesToDrop(cluster), "with the database gone, the persisted list");
        backend.stop(cluster, true);

        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname LIKE 'd" + id
                + "%' OR rolname IN ('dsqlowner_" + id + "', 'dsqlconnect_" + id + "')"),
                "the role created in the cluster is dropped too");
    }

    @Test
    void failedBootstrapCleansUpWhatItCreated() throws Exception {
        String id = "qrstuvwxyzabcdefghijklmnop";
        String database = "dsql_" + id;
        DsqlCluster cluster = cluster(id);
        try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
            statement.execute("CREATE ROLE someone_else NOLOGIN");
            statement.execute("CREATE DATABASE " + database + " OWNER someone_else");
            // Marked as this cluster's, so the start goes as far as the bootstrap.
            statement.execute("COMMENT ON DATABASE " + database + " IS '" + ExternalDsqlBackend.marker(cluster) + "'");
        }
        try {
            try (Connection existing = connect(database); Statement statement = existing.createStatement()) {
                statement.execute("CREATE SCHEMA sys AUTHORIZATION someone_else");
                statement.execute("CREATE TABLE sys.backend_roles (unexpected int)");
                statement.execute("ALTER TABLE sys.backend_roles OWNER TO someone_else");
            }

            boolean failed = false;
            try {
                new ExternalDsqlBackend(config()).start(cluster);
            } catch (SQLException e) {
                failed = true;
            }

            assertTrue(failed, "the bootstrap fails on a sys it does not own");
            assertEquals("someone_else", single("postgres", "SELECT datdba::regrole::text FROM pg_database"
                    + " WHERE datname = '" + database + "'"), "the database is left as it was");
            assertEquals("someone_else", single(database, "SELECT nspowner::regrole::text FROM pg_namespace"
                    + " WHERE nspname = 'sys'"));
            assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('d" + id
                    + "_admin', 'dsqlowner_" + id + "', 'dsqlconnect_" + id + "')"),
                    "the admin, owner and connect role this attempt created are dropped");
            assertEquals("1", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname = 'someone_else'"));
        } finally {
            try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
                statement.execute("DROP ROLE IF EXISTS someone_else");
            }
        }
    }

    @Test
    void aLoginStillRecordsItsSessionWhenAnotherPurgesTheSameEndedOne() throws Exception {
        String id = "stuvwxyzabcdefghijklmnopqr";
        String database = "dsql_" + id;
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(id);
        DsqlDatabases.Backend started = backend.start(cluster);
        @SuppressWarnings("unchecked")
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> resourcePolicyProviders = mock(Instance.class);
        DsqlEndpoint endpoint = new DsqlEndpoint(mock(DsqlTlsCertificates.class), mock(IamService.class),
                mock(IamPolicyEvaluator.class), scpProvider, resourcePolicyProviders, mock(DsqlPrincipals.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), Clock.systemUTC());
        try (Connection other = connect(database); Statement statement = other.createStatement()) {
            // A session that has ended, which both logins purge.
            statement.execute("INSERT INTO sys.session_identity VALUES (2147483000, 'ended', 'ended')");
            other.setAutoCommit(false);
            statement.execute("DELETE FROM sys.session_identity WHERE pid = 2147483000");

            CompletableFuture<Boolean> recorded = CompletableFuture.supplyAsync(() ->
                    endpoint.recordSession(started, 2147483001, "arn:aws:iam::111122223333:root", "s1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> "1".equals(single("postgres",
                    "SELECT count(*)::text FROM pg_stat_activity WHERE datname = '" + database
                            + "' AND wait_event_type = 'Lock'")));
            other.commit();

            assertTrue(recorded.get(30, TimeUnit.SECONDS), "the login's purge passes over the row already gone");
            assertEquals("s1", single(database, "SELECT session_id FROM sys.session_identity WHERE pid = 2147483001"));
        } finally {
            endpoint.stop();
            cluster.setPendingBackendRoles(backend.rolesToDrop(cluster));
            backend.stop(cluster, true);
        }
    }

    @Test
    void anUnmarkedDatabaseFailsTheStartAndIsLeftUntouched() throws Exception {
        String id = "aaaaabbbbbcccccdddddeeeeef";
        String database = "dsql_" + id;
        onServer("CREATE DATABASE " + database);
        try {
            DsqlCluster cluster = cluster(id);

            SQLException refused = assertThrows(SQLException.class, () -> new ExternalDsqlBackend(config()).start(cluster));

            assertTrue(refused.getMessage().contains("database \"" + database + "\" that Floci did not create"),
                    refused.getMessage());
            assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('d" + id
                    + "_admin', 'dsqlowner_" + id + "', 'dsqlconnect_" + id + "')"), "no role was created");
            assertEquals("true", single("postgres", "SELECT has_database_privilege('public', '" + database
                    + "', 'CONNECT')::text"), "its privileges are as they were");
            assertEquals("true", single("postgres", "SELECT (shobj_description(oid, 'pg_database') IS NULL)::text"
                    + " FROM pg_database WHERE datname = '" + database + "'"), "and it is not marked");
            assertEquals("0", single(database, "SELECT count(*)::text FROM pg_namespace WHERE nspname = 'sys'"),
                    "nothing was bootstrapped in it");
        } finally {
            onServer("DROP DATABASE IF EXISTS " + database);
        }
    }

    @Test
    void anUnmarkedOrForeignAdminFailsTheStartAndIsLeftUntouched() throws Exception {
        String id = "fffffgggggbbbbbcccccdddddz";
        String admin = "d" + id + "_admin";
        for (String comment : new String[] {null, "floci:dsql:arn:aws:dsql:eu-west-1:111122223333:cluster/other"}) {
            onServer("CREATE ROLE " + admin + " LOGIN PASSWORD 'theirs'");
            if (comment != null) {
                onServer("COMMENT ON ROLE " + admin + " IS '" + comment + "'");
            }
            String password = single("postgres", "SELECT rolpassword FROM pg_authid WHERE rolname = '" + admin + "'");
            try {
                DsqlCluster cluster = cluster(id);

                SQLException refused = assertThrows(SQLException.class, () ->
                        new ExternalDsqlBackend(config()).start(cluster));

                assertTrue(refused.getMessage().contains("role \"" + admin + "\" that Floci did not create"),
                        refused.getMessage());
                assertEquals(password, single("postgres", "SELECT rolpassword FROM pg_authid WHERE rolname = '"
                        + admin + "'"), "its password is as it was");
                assertEquals(String.valueOf(comment), String.valueOf(single("postgres", "SELECT shobj_description(oid,"
                        + " 'pg_authid') FROM pg_roles WHERE rolname = '" + admin + "'")), "so is its comment");
                assertEquals("false", single("postgres", "SELECT rolcreaterole::text FROM pg_roles WHERE rolname = '"
                        + admin + "'"));
                assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('dsqlowner_"
                        + id + "', 'dsqlconnect_" + id + "')"), "no owner or connect role was created");
                assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_"
                        + id + "'"), "no database was created");
            } finally {
                onServer("DROP ROLE IF EXISTS " + admin);
            }
        }
    }

    @Test
    void aForeignConnectRoleFailsTheStartAndIsLeftUntouched() throws Exception {
        String id = "jjjjjkkkkklllllmmmmmnnnnno";
        String connect = "dsqlconnect_" + id;
        onServer("CREATE ROLE " + connect + " NOLOGIN");
        onServer("COMMENT ON ROLE " + connect + " IS 'someone else''s'");
        try {
            SQLException refused = assertThrows(SQLException.class, () ->
                    new ExternalDsqlBackend(config()).start(cluster(id)));

            assertTrue(refused.getMessage().contains("role \"" + connect + "\" that Floci did not create"),
                    refused.getMessage());
            assertEquals("someone else's", single("postgres", "SELECT shobj_description(oid, 'pg_authid')"
                    + " FROM pg_roles WHERE rolname = '" + connect + "'"));
            assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('d" + id
                    + "_admin', 'dsqlowner_" + id + "')"), "no other role was created");
            assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_"
                    + id + "'"), "nor the database");
        } finally {
            onServer("DROP ROLE IF EXISTS " + connect);
        }
    }

    @Test
    void deletionLeavesUnmarkedObjectsAlone() throws Exception {
        String id = "hhhhhiiiiijjjjjkkkkklllllm";
        String database = "dsql_" + id;
        String admin = "d" + id + "_admin";
        String owner = "dsqlowner_" + id;
        onServer("CREATE ROLE " + owner + " NOLOGIN");
        onServer("CREATE ROLE " + admin + " LOGIN");
        onServer("CREATE DATABASE " + database + " OWNER " + owner);
        try {
            ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
            DsqlCluster cluster = cluster(id);

            List<String> roles = backend.rolesToDrop(cluster);
            cluster.setPendingBackendRoles(roles);
            backend.stop(cluster, true);

            assertEquals(List.of(admin, "dsqlconnect_" + id, owner), roles);
            assertEquals("1", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = '"
                    + database + "'"), "a database without the marker is left");
            assertEquals("2", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('" + admin
                    + "', '" + owner + "')"), "so are an admin and owner without it");
        } finally {
            onServer("DROP DATABASE IF EXISTS " + database);
            onServer("DROP ROLE IF EXISTS " + admin);
            onServer("DROP ROLE IF EXISTS " + owner);
        }
    }

    @Test
    void anUnstampedDatabaseOwnedByTheMarkedOwnerIsAdoptedAndStamped() throws Exception {
        String id = "sssssttttttuuuuuvvvvvwwwww";
        String database = "dsql_" + id;
        String owner = "dsqlowner_" + id;
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(id);
        String marker = ExternalDsqlBackend.marker(cluster);
        // A start that stopped between CREATE DATABASE and its COMMENT.
        onServer("CREATE ROLE " + owner + " NOLOGIN");
        onServer("COMMENT ON ROLE " + owner + " IS '" + marker + "'");
        onServer("CREATE DATABASE " + database + " OWNER " + owner);
        try {
            backend.start(cluster);

            assertEquals(marker, single("postgres", "SELECT shobj_description(oid, 'pg_database') FROM pg_database"
                    + " WHERE datname = '" + database + "'"), "the database is stamped");
        } finally {
            cluster.setPendingBackendRoles(backend.rolesToDrop(cluster));
            backend.stop(cluster, true);
        }
        assertEquals("0", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = '" + database
                + "'"), "and deleted with the cluster");
    }

    @Test
    void aDatabaseOwnedByTheMarkedOwnerButCommentedOtherwiseIsStillRefused() throws Exception {
        String id = "xxxxxyyyyyzzzzzaaaaabbbbbc";
        String database = "dsql_" + id;
        String owner = "dsqlowner_" + id;
        DsqlCluster cluster = cluster(id);
        onServer("CREATE ROLE " + owner + " NOLOGIN");
        onServer("COMMENT ON ROLE " + owner + " IS '" + ExternalDsqlBackend.marker(cluster) + "'");
        onServer("CREATE DATABASE " + database + " OWNER " + owner);
        onServer("COMMENT ON DATABASE " + database + " IS 'someone else''s'");
        try {
            SQLException refused = assertThrows(SQLException.class, () ->
                    new ExternalDsqlBackend(config()).start(cluster));

            assertTrue(refused.getMessage().contains("database \"" + database + "\""), refused.getMessage());
            assertEquals("someone else's", single("postgres", "SELECT shobj_description(oid, 'pg_database')"
                    + " FROM pg_database WHERE datname = '" + database + "'"));
        } finally {
            onServer("DROP DATABASE IF EXISTS " + database);
            onServer("DROP ROLE IF EXISTS " + owner);
        }
    }

    @Test
    void aClusterWithoutAnArnIsDeletedWithoutDroppingWhatItCannotProveIsItsOwn() throws Exception {
        String id = "cccccdddddeeeeefffffgggggh";
        ExternalDsqlBackend backend = new ExternalDsqlBackend(config());
        DsqlCluster cluster = cluster(id);
        backend.start(cluster);
        DsqlCluster withoutArn = new DsqlCluster();
        withoutArn.setIdentifier(id);
        try {
            List<String> roles = backend.rolesToDrop(withoutArn);
            withoutArn.setPendingBackendRoles(roles);
            backend.stop(withoutArn, true);

            assertEquals("1", single("postgres", "SELECT count(*)::text FROM pg_database WHERE datname = 'dsql_"
                    + id + "'"), "the database is left");
            assertEquals("3", single("postgres", "SELECT count(*)::text FROM pg_roles WHERE rolname IN ('d" + id
                    + "_admin', 'dsqlowner_" + id + "', 'dsqlconnect_" + id + "')"), "so are the admin, owner and connect role");
        } finally {
            cluster.setPendingBackendRoles(backend.rolesToDrop(cluster));
            backend.stop(cluster, true);
        }
    }

    @Test
    void aWrongSuperuserPasswordFailsTheStartAtOnce() {
        EmulatorConfig wrong = config();
        when(wrong.services().dsql().external().password()).thenReturn(Optional.of("not-the-password"));
        long started = System.nanoTime();

        SQLException refused = assertThrows(SQLException.class, () ->
                new ExternalDsqlBackend(wrong).start(cluster("nnnnnooooopppppqqqqqrrrrrs")));

        assertEquals("28P01", refused.getSQLState());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
                "a refused login does not wait out the 90 s a starting server gets");
    }

    private static DsqlCluster cluster(String id) {
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier(id);
        cluster.setArn("arn:aws:dsql:eu-west-1:111122223333:cluster/" + id);
        return cluster;
    }

    private void onServer(String sql) throws SQLException {
        try (Connection server = connect("postgres"); Statement statement = server.createStatement()) {
            statement.execute(sql);
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
        return DriverManager.getConnection(server.jdbcUrl(database), "postgres", PASSWORD);
    }
}
