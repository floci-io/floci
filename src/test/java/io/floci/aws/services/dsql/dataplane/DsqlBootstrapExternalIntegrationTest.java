package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.services.dsql.ExternalPostgresServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the bootstrap script rendered for the external backend against a throwaway PostgreSQL 16,
 * set up as a shared server holds one cluster: its own database, owner role and prefixed roles.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DsqlBootstrapExternalIntegrationTest {

    private static final String DATABASE = "dsql_abc";
    private static final String OWNER = "dsqlowner_abc";
    private static final String ADMIN = "dabc_admin";
    private static final String PREFIX = "dabc_";
    private static final String OTHER_DATABASE = "dsql_xyz";
    private static final String OTHER_OWNER = "dsqlowner_xyz";
    private static final String OTHER_ADMIN = "dxyz_admin";
    private static final String OTHER_PREFIX = "dxyz_";
    private static final String ARN = "arn:aws:iam::123456789012:role/app";

    private ExternalPostgresServer server;

    @BeforeAll
    void startServerAndBootstrapTwice() throws Exception {
        try {
            server = ExternalPostgresServer.start("floci-dsql-bootstrap", "", "POSTGRES_HOST_AUTH_METHOD=trust");
        } catch (ExternalPostgresServer.DockerUnavailableException e) {
            throw new TestAbortedException(e.getMessage(), e);
        }

        createClusterDatabase(DATABASE, OWNER);
        createClusterDatabase(OTHER_DATABASE, OTHER_OWNER);
        String script = DsqlBootstrap.render(DATABASE, OWNER, ADMIN, PREFIX, true);
        for (int run = 0; run < 2; run++) {
            try (Connection connection = connect(DATABASE, "postgres"); Statement statement = connection.createStatement()) {
                statement.execute(script);
            }
        }
        try (Connection connection = connect(OTHER_DATABASE, "postgres"); Statement statement = connection.createStatement()) {
            statement.execute(DsqlBootstrap.render(OTHER_DATABASE, OTHER_OWNER, OTHER_ADMIN, OTHER_PREFIX, true));
        }
    }

    private void createClusterDatabase(String database, String owner) throws SQLException {
        try (Connection connection = connect("postgres", "postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + owner + " NOLOGIN");
            statement.execute("CREATE DATABASE " + database + " OWNER " + owner);
            statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
        }
    }

    @AfterAll
    void removeServer() {
        if (server != null) {
            server.close();
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
        assertEquals(List.of("admin", "b", "c"), visible, "sys.roles lists the cluster's roles, not its owner");

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

    @Test
    void aRoleGrantedToAnotherClustersAdminStaysItsOwnClusters() throws SQLException {
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "x$$)");
            statement.execute("CALL sys.run_role_ddl($$GRANT " + PREFIX + "x TO " + OTHER_ADMIN + " WITH ADMIN OPTION$$)");
        }
        try (Connection superuser = connect(OTHER_DATABASE, "postgres"); Statement statement = superuser.createStatement()) {
            statement.execute("CALL sys._record_backend_roles()");
            assertFalse(backendRoles(OTHER_DATABASE).containsValue(PREFIX + "x"),
                    "another cluster's role is not recorded through its admin's ADMIN option");
            assertEquals(List.of("admin"), query(OTHER_DATABASE, OTHER_ADMIN, "SELECT rolname FROM sys.roles"));

            // A role recorded before the prefix was required is forgotten, and loses its CONNECT.
            statement.execute("INSERT INTO sys.backend_roles SELECT oid, rolname, rolname FROM pg_roles WHERE rolname = '"
                    + PREFIX + "x'");
            statement.execute("GRANT CONNECT ON DATABASE " + OTHER_DATABASE + " TO " + PREFIX + "x");
            statement.execute("CALL sys._record_backend_roles()");
            assertFalse(backendRoles(OTHER_DATABASE).containsValue(PREFIX + "x"));
            assertEquals(List.of("false"), query(OTHER_DATABASE, "postgres",
                    "SELECT has_database_privilege('" + PREFIX + "x', '" + OTHER_DATABASE + "', 'CONNECT')::text"));
        }
        assertEquals(PREFIX + "x", backendRoles().get("x"), "the role stays recorded by its own cluster");
        assertEquals(OTHER_OWNER, backendRoles(OTHER_DATABASE).get(OTHER_OWNER), "the owner stays recorded");
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "x']::text[], false)");
        }
    }

    @Test
    void aRolePlantedUnderThisClustersPrefixByAnotherClusterIsNotRecorded() throws SQLException {
        String planted = PREFIX + "planted";
        // Another cluster's admin, on the server itself, where Floci translates no name.
        try (Connection other = connect(OTHER_DATABASE, OTHER_ADMIN); Statement statement = other.createStatement()) {
            statement.execute("CREATE ROLE " + planted);
            statement.execute("GRANT " + planted + " TO " + ADMIN + " WITH ADMIN OPTION");
            statement.execute("CREATE TABLE planted_in_other(x int)");
            statement.execute("GRANT SELECT ON planted_in_other TO " + planted);
        }
        try {
            assertEquals(List.of("true"), query("postgres", "SELECT pg_has_role('" + ADMIN + "', '" + planted
                    + "', 'MEMBER WITH ADMIN OPTION')::text"), "this cluster's admin holds the ADMIN option on it");
            try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
                statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "own$$)");
            }

            Map<String, String> recorded = backendRoles();
            assertFalse(recorded.containsValue(planted), "a role the other cluster's admin granted in is not recorded");
            assertEquals(PREFIX + "own", recorded.get("own"), "a role this cluster's admin created is");
            assertFalse(hasConnect(planted));
        } finally {
            try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
                statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "own']::text[], false)");
            }
            try (Connection superuser = connect(DATABASE, "postgres"); Statement statement = superuser.createStatement()) {
                // Undoes what a recording of the planted role would have done, so the role can go.
                statement.execute("DELETE FROM sys.backend_roles WHERE backend_name = '" + planted + "'");
                statement.execute("REVOKE CONNECT ON DATABASE " + DATABASE + " FROM " + planted);
            }
            try (Connection other = connect(OTHER_DATABASE, OTHER_ADMIN); Statement statement = other.createStatement()) {
                statement.execute("DROP TABLE planted_in_other");
                statement.execute("DROP ROLE " + planted);
            }
        }
    }

    @Test
    void theImplicitGrantOfARoleToItsCreatorIsMadeByTheBootstrapSuperuser() throws SQLException {
        try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
            statement.execute("CALL sys.run_role_ddl($$CREATE ROLE " + PREFIX + "made$$)");
        }
        try {
            // What sys._record_backend_roles matches: PostgreSQL 16 records BOOTSTRAP_SUPERUSERID (10).
            assertEquals(List.of("10"), query("postgres", "SELECT m.grantor::text FROM pg_auth_members m"
                    + " WHERE m.roleid = '" + PREFIX + "made'::regrole AND m.member = '" + ADMIN + "'::regrole"
                    + " AND m.admin_option"));
        } finally {
            try (Connection admin = connect(DATABASE, ADMIN); Statement statement = admin.createStatement()) {
                statement.execute("CALL sys.drop_roles(ARRAY['" + PREFIX + "made']::text[], false)");
            }
        }
    }

    private Map<String, String> backendRoles() throws SQLException {
        return backendRoles(DATABASE);
    }

    private Map<String, String> backendRoles(String database) throws SQLException {
        Map<String, String> roles = new TreeMap<>();
        try (Connection connection = connect(database, "postgres"); Statement statement = connection.createStatement();
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
        return query(DATABASE, user, sql);
    }

    private List<String> query(String database, String user, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(database, user); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private Connection connect(String database, String user) throws SQLException {
        return DriverManager.getConnection(server.jdbcUrl(database), user, "");
    }
}
