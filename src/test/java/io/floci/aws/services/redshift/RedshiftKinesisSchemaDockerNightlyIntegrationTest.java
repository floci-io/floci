package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.spectrum.KinesisSchemaRegistry;
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

/** Creates and drops external schemas over Kinesis through a real cluster connection. */
@QuarkusTest
class RedshiftKinesisSchemaDockerNightlyIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/KinesisSchemaItRole";

    @Inject
    RedshiftService redshiftService;

    @Inject
    KinesisSchemaRegistry kinesisSchemas;

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
        return kinesisSchemas.find(ACCOUNT, ACCOUNT + ":" + clusterId, "dev", schema).isPresent();
    }

    private static boolean exists(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    @Test
    @Timeout(120)
    void createsListsAndDropsAKinesisSchema() throws Exception {
        clusterId = "it-kds-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA kds FROM KINESIS REGION 'us-west-2' IAM_ROLE '" + ROLE_ARN + "'");

            assertTrue(bound("kds"));
            assertTrue(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'kds'"));
            try (ResultSet rows = statement.executeQuery(
                    "SELECT eskind, esoptions FROM svv_external_schemas WHERE schemaname = 'kds'")) {
                assertTrue(rows.next());
                assertEquals(9, rows.getInt("eskind"));
                assertTrue(rows.getString("esoptions").contains(ROLE_ARN));
                assertFalse(rows.next());
            }

            SQLException duplicate = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE EXTERNAL SCHEMA kds FROM KINESIS IAM_ROLE '" + ROLE_ARN + "'"));
            assertEquals("42P06", duplicate.getSQLState());
            statement.execute("CREATE EXTERNAL SCHEMA IF NOT EXISTS kds FROM KINESIS IAM_ROLE '" + ROLE_ARN + "'");

            SQLException read = assertThrows(SQLException.class, () -> statement.execute("SELECT * FROM kds.my_stream"));
            assertEquals("0A000", read.getSQLState());

            statement.execute("DROP SCHEMA kds");

            assertFalse(bound("kds"));
            assertFalse(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'kds'"));
            assertFalse(exists(statement, "SELECT 1 FROM svv_external_schemas WHERE schemaname = 'kds'"));
        }
    }

    @Test
    @Timeout(120)
    void refusesToCreateTheSchemaInsideATransaction() throws Exception {
        clusterId = "it-kds-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(
                        "CREATE EXTERNAL SCHEMA kds FROM KINESIS IAM_ROLE '" + ROLE_ARN + "'"));
                assertEquals("0A000", rejected.getSQLState());
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertFalse(bound("kds"));
            assertFalse(exists(statement, "SELECT 1 FROM pg_namespace WHERE nspname = 'kds'"));
        }
    }
}
