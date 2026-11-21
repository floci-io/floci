package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.spectrum.MskSchemaRegistry;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Creates and drops external schemas over Amazon MSK through a real cluster connection. */
@QuarkusTest
class RedshiftMskSchemaIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/MskSchemaItRole";
    private static final String CLUSTER_ARN = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";

    @Inject
    RedshiftService redshiftService;

    @Inject
    MskSchemaRegistry mskSchemas;

    private String clusterId;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift integration tests");
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
    }

    private boolean bound(String schema) {
        return mskSchemas.find(ACCOUNT, ACCOUNT + ":" + clusterId, "dev", schema).isPresent();
    }

    private static boolean exists(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    @Test
    @Timeout(120)
    void createsListsAndDropsAnMskSchema() throws Exception {
        clusterId = "it-msk-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE_ARN
                    + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER_ARN + "'");

            assertTrue(bound("msk"));
            assertTrue(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'msk'"));
            try (ResultSet rows = statement.executeQuery(
                    "SELECT eskind, esoptions FROM svv_external_schemas WHERE schemaname = 'msk'")) {
                assertTrue(rows.next());
                assertEquals(10, rows.getInt("eskind"));
                String options = rows.getString("esoptions");
                assertTrue(options.contains(ROLE_ARN));
                assertTrue(options.contains(CLUSTER_ARN));
                assertTrue(options.contains("IAM"));
                assertFalse(rows.next());
            }

            SQLException duplicate = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE_ARN
                            + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER_ARN + "'"));
            assertEquals("42P06", duplicate.getSQLState());
            statement.execute("CREATE EXTERNAL SCHEMA IF NOT EXISTS msk FROM MSK IAM_ROLE '" + ROLE_ARN
                    + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER_ARN + "'");

            SQLException read = assertThrows(SQLException.class, () -> statement.execute("SELECT * FROM msk.orders"));
            assertEquals("0A000", read.getSQLState());

            statement.execute("DROP SCHEMA msk");

            assertFalse(bound("msk"));
            assertFalse(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'msk'"));
            assertFalse(exists(statement, "SELECT 1 FROM svv_external_schemas WHERE schemaname = 'msk'"));
        }
    }

    @Test
    @Timeout(120)
    void refusesToCreateTheSchemaInsideATransaction() throws Exception {
        clusterId = "it-msk-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(
                        "CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE_ARN
                                + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER_ARN + "'"));
                assertEquals("0A000", rejected.getSQLState());
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertFalse(bound("msk"));
            assertFalse(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'msk'"));
        }
    }
}
