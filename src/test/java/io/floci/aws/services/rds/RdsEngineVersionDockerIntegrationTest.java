package io.floci.aws.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Tag("docker")
class RdsEngineVersionDockerIntegrationTest {

    private static final String PASSWORD = "Passw0rd12345";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @Test
    void minorUpgradePreservesDataAndMajorUpgradeIsRefused() throws SQLException {
        String id = "engine-version-" + Long.toString(System.nanoTime(), 36);
        try (RdsClient client = rdsClient("us-east-1", "000000000000")) {
            client.createDBInstance(b -> b.dbInstanceIdentifier(id).engine("postgres").engineVersion("16.4")
                    .dbInstanceClass("db.t3.micro").allocatedStorage(20).dbName("appdb")
                    .masterUsername("admin").masterUserPassword(PASSWORD));
            try {
                DBInstance original = describe(client, id);
                int port = original.endpoint().port();
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                        assertTrue(query(port, "SHOW server_version").startsWith("16.4")));
                try (Connection connection = connect(port); Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE upgrade_probe (value TEXT)");
                    statement.execute("INSERT INTO upgrade_probe VALUES ('preserved')");
                }

                for (String target : new String[] {"17.2"}) {
                    for (boolean immediately : new boolean[] {true, false}) {
                        RdsException error = assertThrows(RdsException.class, () ->
                                client.modifyDBInstance(b -> b.dbInstanceIdentifier(id).engineVersion(target)
                                        .allowMajorVersionUpgrade(true).applyImmediately(immediately)
                                        .dbInstanceClass("db.t3.large").allocatedStorage(100)
                                        .masterUserPassword("ChangedPassw0rd12345")));

                        assertEquals(400, error.statusCode());
                        assertEquals("InvalidParameterCombination", error.awsErrorDetails().errorCode());
                        assertTrue(error.getMessage().contains("container-backed"));
                        DBInstance unchanged = describe(client, id);
                        assertEquals("16.4", unchanged.engineVersion());
                        assertEquals("available", unchanged.dbInstanceStatus());
                        assertEquals(original.endpoint(), unchanged.endpoint());
                        assertEquals("db.t3.micro", unchanged.dbInstanceClass());
                        assertEquals(20, unchanged.allocatedStorage());
                        assertTrue(query(port, "SHOW server_version").startsWith("16.4"));
                        assertEquals("preserved", query(port, "SELECT value FROM upgrade_probe"));
                    }
                }

                DBInstance sameVersion = client.modifyDBInstance(b -> b.dbInstanceIdentifier(id)
                        .engineVersion("16.4").dbInstanceClass("db.t3.large").applyImmediately(true)).dbInstance();
                assertEquals("16.4", sameVersion.engineVersion());
                assertEquals("db.t3.large", sameVersion.dbInstanceClass());
                assertEquals("preserved", query(port, "SELECT value FROM upgrade_probe"));

                DBInstance upgraded = client.modifyDBInstance(b -> b.dbInstanceIdentifier(id)
                        .engineVersion("16.5").allocatedStorage(100).applyImmediately(true)).dbInstance();
                assertEquals("16.5", upgraded.engineVersion());
                assertEquals("available", upgraded.dbInstanceStatus());
                assertEquals(original.endpoint(), upgraded.endpoint());
                assertEquals("16.5", describe(client, id).engineVersion());
                assertTrue(query(port, "SHOW server_version").startsWith("16.5"));
                assertEquals("preserved", query(port, "SELECT value FROM upgrade_probe"));
            } finally {
                client.deleteDBInstance(b -> b.dbInstanceIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    private static DBInstance describe(RdsClient client, String id) {
        return client.describeDBInstances(b -> b.dbInstanceIdentifier(id)).dbInstances().getFirst();
    }

    private static Connection connect(int port) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port
                + "/appdb?sslmode=disable&connectTimeout=5&socketTimeout=5", "admin", PASSWORD);
    }

    private static String query(int port, String sql) throws SQLException {
        try (Connection connection = connect(port);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
