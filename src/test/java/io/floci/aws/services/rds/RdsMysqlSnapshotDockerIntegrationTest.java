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
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A MySQL or MariaDB instance's snapshot carries its schemas, its roles and its accounts: an
 * instance restored from it holds the same rows, including a schema whose name has a space, and an
 * account created on the source logs in to the restored instance with its own password and reads
 * through the role it was granted. A snapshot requested straight after CreateDBInstance waits for
 * the image to finish initializing the instance. Skipped without Docker.
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
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "mysql | 8.0 | SET DEFAULT ROLE 'readers' TO 'reader'@'%'",
            "mariadb | 10.11 | SET DEFAULT ROLE 'readers' FOR 'reader'@'%'"})
    void restoredInstanceHoldsTheSourcesRowsRolesAndAccounts(String engine, String version, String defaultRole) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String sourceId = engine + "-snap-src-" + suffix;
        String restoredId = engine + "-snap-dst-" + suffix;
        String snapshotId = engine + "-snap-" + suffix;
        String earlySnapshotId = engine + "-snap-early-" + suffix;
        try {
            int sourcePort = createInstance(sourceId, engine, version);
            rds("CreateDBSnapshot")
                    .formParam("DBInstanceIdentifier", sourceId)
                    .formParam("DBSnapshotIdentifier", earlySnapshotId)
            .when().post("/").then().statusCode(200);
            execute(sourcePort, MASTER_USER, MASTER_PASSWORD,
                    "CREATE TABLE appdb.notes (id INT PRIMARY KEY, body VARCHAR(32))",
                    "INSERT INTO appdb.notes VALUES (1, 'kept')",
                    "CREATE DATABASE `sales data`",
                    "CREATE TABLE `sales data`.totals (amount INT)",
                    "INSERT INTO `sales data`.totals VALUES (7)",
                    "CREATE ROLE 'readers'",
                    "GRANT SELECT ON appdb.* TO 'readers'",
                    "CREATE USER 'reader'@'%' IDENTIFIED BY 'R3aderPass'",
                    "GRANT 'readers' TO 'reader'@'%'",
                    defaultRole);

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
            assertEquals("7", query(restoredPort, MASTER_USER, MASTER_PASSWORD,
                    "SELECT amount FROM `sales data`.totals"));
            assertEquals("1", query(restoredPort, "reader", "R3aderPass", "SELECT COUNT(*) FROM appdb.notes"));
        } finally {
            deleteInstance(restoredId);
            deleteInstance(sourceId);
            for (String id : new String[] {snapshotId, earlySnapshotId}) {
                rds("DeleteDBSnapshot")
                        .formParam("DBSnapshotIdentifier", id)
                .when().post("/");
            }
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

    /** Runs the statements in one connection, once the server accepts it (see {@link #whenConnected}). */
    private static void execute(int port, String user, String password, String... statements) {
        whenConnected(port, user, password, connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    /** The first column of a query's first row, once the server accepts the connection. */
    private static String query(int port, String user, String password, String sql) {
        return whenConnected(port, user, password, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getString(1);
            }
        });
    }

    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} in a new connection. Floci reports an instance before its image has
     * initialized the data directory, on a server that does not listen on TCP, so a refused
     * connection is tried again until the server it then starts answers.
     */
    private static <T> T whenConnected(int port, String user, String password, SqlWork<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(90)).pollDelay(Duration.ZERO).pollInterval(Duration.ofSeconds(1))
                .ignoreExceptionsInstanceOf(SQLException.class)
                .until(() -> {
                    try (Connection connection = connect(port, user, password)) {
                        result.set(work.run(connection));
                        return true;
                    }
                });
        return result.get();
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
