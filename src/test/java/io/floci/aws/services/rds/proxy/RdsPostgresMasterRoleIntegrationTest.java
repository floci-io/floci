package io.floci.aws.services.rds.proxy;

import io.floci.aws.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;

import static io.floci.aws.testing.RdsRequests.createPostgresInstance;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A PostgreSQL instance is initialized as RDS initializes one: {@code rdsadmin} is the bootstrap superuser and the
 * master user is an ordinary role in {@code rds_superuser} (RDS User Guide, "Understanding the rds_superuser
 * role"), so the master's objects can be reassigned, and IAM sessions are still handed over to their roles.
 */
@QuarkusTest
@Tag("docker")
class RdsPostgresMasterRoleIntegrationTest {
    private static final String MASTER = "master";
    private static final String PASSWORD = "Master123!";

    @Test
    void theMasterIsAnOrdinaryRoleInRdsSuperuserAndKeepsItThroughASnapshot() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String id = "master-role-" + suffix;
        String snapshot = id + "-snap";
        String restored = id + "-restored";
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            try (Connection connection = connect(port, "appdb", login(MASTER, PASSWORD));
                 Statement sql = connection.createStatement()) {
                assertEquals("false|true|true|false|false|infinity", row(sql, "SELECT rolsuper || '|' || "
                        + "rolcreaterole || '|' || rolcreatedb || '|' || rolreplication || '|' || rolbypassrls "
                        + "|| '|' || rolvaliduntil FROM pg_roles WHERE rolname = current_user"));
                assertEquals("true|true", row(sql, "SELECT pg_has_role(current_user, 'rds_superuser', 'member') || '|' "
                        + "|| pg_has_role('rds_superuser', 'rds_replication', 'member')"));
                assertEquals("rdsadmin", row(sql, "SELECT rolname FROM pg_roles WHERE oid = 10"));
                assertEquals("appdb=master,postgres=master,rdsadmin=rdsadmin,template1=master", row(sql,
                        "SELECT string_agg(datname || '=' || pg_get_userbyid(datdba), ',' ORDER BY datname) "
                                + "FROM pg_database WHERE datname <> 'template0'"));

                sql.execute("CREATE TABLE owned (id int)");
                sql.execute("CREATE ROLE app_rw");
                sql.execute("GRANT app_rw TO master");
                sql.execute("REASSIGN OWNED BY master TO app_rw");
                assertEquals("app_rw", row(sql, "SELECT tableowner FROM pg_tables WHERE tablename = 'owned'"));

                sql.execute("CREATE ROLE cdc LOGIN");
                sql.execute("GRANT rds_replication TO cdc");
                sql.execute("CREATE ROLE app_user LOGIN");
                sql.execute("GRANT rds_iam TO app_user");
            }
            assertSessionUser(port, login(MASTER, PASSWORD), MASTER);
            assertSessionUser(port, token(port, "app_user"), "app_user");
            assertRefused(port, "appdb", login("rdsadmin", PASSWORD), "28P01");
            assertRefused(port, "appdb", token(port, "rdsadmin"), "28P01");
            assertRefused(port, "rdsadmin", login(MASTER, PASSWORD), "42501");

            rds("CreateDBSnapshot").formParam("DBInstanceIdentifier", id)
                    .formParam("DBSnapshotIdentifier", snapshot).when().post("/").then().statusCode(200);
            rds("RestoreDBInstanceFromDBSnapshot").formParam("DBInstanceIdentifier", restored)
                    .formParam("DBSnapshotIdentifier", snapshot).formParam("EnableIAMDatabaseAuthentication", true)
                    .when().post("/").then().statusCode(200);
            int restoredPort = availablePort(restored);
            try (Connection connection = connect(restoredPort, "appdb", login(MASTER, PASSWORD));
                 Statement sql = connection.createStatement()) {
                assertEquals("false|rdsadmin|app_rw", row(sql, "SELECT (SELECT rolsuper::text FROM pg_roles "
                        + "WHERE rolname = current_user) || '|' || (SELECT rolname FROM pg_roles WHERE oid = 10) "
                        + "|| '|' || (SELECT tableowner FROM pg_tables WHERE tablename = 'owned')"));
            }
            assertSessionUser(restoredPort, token(restoredPort, "app_user"), "app_user");
        } finally {
            deleteInstance(restored);
            rds("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", snapshot).when().post("/");
            deleteInstance(id);
        }
    }

    private static String row(Statement sql, String query) throws SQLException {
        try (ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void assertSessionUser(int port, Properties properties, String user) throws SQLException {
        try (Connection connection = connect(port, "appdb", properties);
             Statement sql = connection.createStatement()) {
            assertEquals(user + "|off", row(sql, "SELECT session_user || '|' || current_setting('is_superuser')"));
        }
    }

    /** Polls the instance until it is available and returns its endpoint port. */
    private static int availablePort(String id) throws InterruptedException {
        String path = "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";
        for (int attempt = 0; attempt < 120; attempt++) {
            XmlPath instance = rds("DescribeDBInstances").formParam("DBInstanceIdentifier", id)
                    .when().post("/").then().statusCode(200).extract().xmlPath();
            if ("available".equals(instance.getString(path + "DBInstanceStatus"))) {
                return instance.getInt(path + "Endpoint.Port");
            }
            Thread.sleep(500);
        }
        throw new AssertionError("DB instance " + id + " did not become available");
    }

    private static void assertRefused(int port, String database, Properties properties, String sqlState) {
        SQLException refusal = assertThrows(SQLException.class, () -> connect(port, database, properties).close());
        assertEquals(sqlState, refusal.getSQLState(), refusal.getMessage());
    }

    private static Properties login(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return properties;
    }

    private static Properties token(int port, String user) throws Exception {
        return login(user, SigV4TokenTestHelper.createRdsToken("localhost", port, user,
                "test", "test", Instant.now(), 900));
    }

    private static Connection connect(int port, String database, Properties properties) throws SQLException {
        return new Driver().connect("jdbc:postgresql://localhost:" + port + "/" + database, properties);
    }
}
