package io.floci.aws.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A MySQL or MariaDB instance's snapshot carries its schemas and its accounts: an instance restored
 * from it holds the same rows, and an account created on the source logs in to the restored
 * instance with its own password. Skipped without Docker.
 */
@QuarkusTest
@Tag("docker")
class RdsMysqlSnapshotDockerIntegrationTest {

    private static final String MASTER_USER = "admin";
    private static final String MASTER_PASSWORD = "Passw0rd12345";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @ParameterizedTest
    @CsvSource({"mysql, 8.0", "mariadb, 10.11"})
    void restoredInstanceHoldsTheSourcesRowsAndAccounts(String engine, String version) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String sourceId = engine + "-snap-src-" + suffix;
        String restoredId = engine + "-snap-dst-" + suffix;
        String snapshotId = engine + "-snap-" + suffix;
        try {
            int sourcePort = createInstance(sourceId, engine, version);
            execute(sourcePort, MASTER_USER, MASTER_PASSWORD,
                    "CREATE TABLE appdb.notes (id INT PRIMARY KEY, body VARCHAR(32))",
                    "INSERT INTO appdb.notes VALUES (1, 'kept')",
                    "CREATE USER 'reader'@'%' IDENTIFIED BY 'R3aderPass'",
                    "GRANT SELECT ON appdb.* TO 'reader'@'%'");

            rds("CreateDBSnapshot")
                    .formParam("DBInstanceIdentifier", sourceId)
                    .formParam("DBSnapshotIdentifier", snapshotId)
            .when().post("/").then().statusCode(200);
            int restoredPort = rds("RestoreDBInstanceFromDBSnapshot")
                    .formParam("DBInstanceIdentifier", restoredId)
                    .formParam("DBSnapshotIdentifier", snapshotId)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("RestoreDBInstanceFromDBSnapshotResponse.RestoreDBInstanceFromDBSnapshotResult"
                            + ".DBInstance.Endpoint.Port");

            assertEquals("kept", query(restoredPort, MASTER_USER, MASTER_PASSWORD,
                    "SELECT body FROM appdb.notes WHERE id = 1"));
            assertEquals("1", query(restoredPort, "reader", "R3aderPass", "SELECT COUNT(*) FROM appdb.notes"));
        } finally {
            deleteInstance(restoredId);
            deleteInstance(sourceId);
            rds("DeleteDBSnapshot")
                    .formParam("DBSnapshotIdentifier", snapshotId)
            .when().post("/");
        }
    }

    private static int createInstance(String instanceId, String engine, String version) {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", instanceId)
                .formParam("Engine", engine)
                .formParam("EngineVersion", version)
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
                .formParam("DBName", "appdb")
        .when().post("/").then().statusCode(200).extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
    }

    /**
     * Runs the statements in one connection. The image restarts its server once after initializing
     * a new data directory, so a refused connection is retried.
     */
    private static void execute(int port, String user, String password, String... statements) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            try (Connection connection = connect(port, user, password);
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                return;
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    /** The first column of a query's first row, retried as {@link #execute} is. */
    private static String query(int port, String user, String password, String sql) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            try (Connection connection = connect(port, user, password);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getString(1);
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    private static Connection connect(int port, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("useSSL", "false");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "10000");
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/", properties);
    }
}
