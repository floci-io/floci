package io.floci.aws.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.BlueGreenDeployment;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PostgreSQL 16 to 17 blue/green upgrade against real containers, through the SDK: the green
 * instance holds the blue rows on the new major version, and after switchover the blue endpoint
 * serves PostgreSQL 17 with those rows while the renamed {@code -old1} instance keeps serving 16
 * on the endpoint green had. Both renamed instances can still be deleted, which needs their
 * containers to have followed them to their new ARNs. Skipped without Docker.
 */
@QuarkusTest
@Tag("docker")
class RdsBlueGreenDeploymentDockerIntegrationTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "Passw0rd12345";

    @BeforeEach
    void checkDocker() throws Exception {
        assumeDocker();
        Class.forName("org.postgresql.Driver");
    }

    @Test
    void switchoverMovesTheBlueEndpointToTheUpgradedGreenDatabase() {
        String blueId = "bg-docker-" + UUID.randomUUID().toString().substring(0, 8);
        String oldId = blueId + "-old1";
        try (RdsClient rds = rdsClient("us-east-1", "000000000000")) {
            DBInstance blue = rds.createDBInstance(request -> request.dbInstanceIdentifier(blueId)
                    .engine("postgres").engineVersion("16.3").dbInstanceClass("db.t3.micro")
                    .allocatedStorage(20).masterUsername(USER).masterUserPassword(PASSWORD)
                    .dbName("appdb").backupRetentionPeriod(1)).dbInstance();
            int bluePort = blue.endpoint().port();
            try {
                execute(bluePort, "CREATE TABLE notes (id int PRIMARY KEY, body text)",
                        "INSERT INTO notes VALUES (1, 'kept')");

                BlueGreenDeployment created = rds.createBlueGreenDeployment(request -> request
                        .blueGreenDeploymentName(blueId).source(blue.dbInstanceArn())
                        .targetEngineVersion("17.4")).blueGreenDeployment();
                assertEquals("AVAILABLE", created.status());
                String greenId = created.target().substring(created.target().lastIndexOf(':') + 1);
                int greenPort = rds.describeDBInstances(request -> request.dbInstanceIdentifier(greenId))
                        .dbInstances().getFirst().endpoint().port();
                assertTrue(query(greenPort, "SHOW server_version").startsWith("17."));
                assertEquals("kept", query(greenPort, "SELECT body FROM notes WHERE id = 1"));

                BlueGreenDeployment switched = rds.switchoverBlueGreenDeployment(request -> request
                        .blueGreenDeploymentIdentifier(created.blueGreenDeploymentIdentifier()))
                        .blueGreenDeployment();
                assertEquals("SWITCHOVER_COMPLETED", switched.status());
                assertEquals(blue.dbInstanceArn(), switched.target());

                DBInstance production = rds.describeDBInstances(request -> request.dbInstanceIdentifier(blueId))
                        .dbInstances().getFirst();
                assertEquals(bluePort, production.endpoint().port());
                assertEquals("17.4", production.engineVersion());
                assertTrue(query(bluePort, "SHOW server_version").startsWith("17."));
                assertEquals("kept", query(bluePort, "SELECT body FROM notes WHERE id = 1"));
                assertTrue(query(greenPort, "SHOW server_version").startsWith("16."));

                rds.deleteBlueGreenDeployment(request -> request
                        .blueGreenDeploymentIdentifier(created.blueGreenDeploymentIdentifier()));
                rds.deleteDBInstance(request -> request.dbInstanceIdentifier(oldId).skipFinalSnapshot(true));
                assertThrows(DbInstanceNotFoundException.class,
                        () -> rds.describeDBInstances(request -> request.dbInstanceIdentifier(oldId)));
            } finally {
                for (String id : new String[] {blueId, oldId}) {
                    try {
                        rds.deleteDBInstance(request -> request.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                    } catch (DbInstanceNotFoundException alreadyGone) {
                        // deleted by the test body
                    }
                }
            }
        }
    }

    private static void execute(int port, String... statements) {
        whenConnected(port, connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    private static String query(int port, String sql) {
        return whenConnected(port, connection -> {
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

    /** Runs {@code work} once the server behind the endpoint accepts connections. */
    private static <T> T whenConnected(int port, SqlWork<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
                .ignoreExceptionsInstanceOf(SQLException.class)
                .until(() -> {
                    try (Connection connection = DriverManager.getConnection(
                            "jdbc:postgresql://127.0.0.1:" + port + "/appdb?connectTimeout=5&socketTimeout=10",
                            USER, PASSWORD)) {
                        result.set(work.run(connection));
                        return true;
                    }
                });
        return result.get();
    }
}
