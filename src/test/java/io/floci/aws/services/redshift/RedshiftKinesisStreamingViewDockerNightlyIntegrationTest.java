package io.floci.aws.services.redshift;

import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Streams a Kinesis stream into a materialized view through a real cluster connection. */
@QuarkusTest
class RedshiftKinesisStreamingViewDockerNightlyIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/KinesisViewItRole";

    @Inject
    RedshiftService redshiftService;

    @Inject
    KinesisService kinesisService;

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

    private void put(String stream, String body) {
        kinesisService.putRecordForAccount(ACCOUNT, stream, body.getBytes(StandardCharsets.UTF_8), "pk", REGION);
    }

    private static List<String> bodies(Statement statement) throws SQLException {
        List<String> bodies = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT body FROM events_mv ORDER BY sequence_number")) {
            while (rows.next()) {
                bodies.add(rows.getString(1));
            }
        }
        return bodies;
    }

    @Test
    @Timeout(180)
    void refreshLoadsOnlyWhatTheStreamGainedAndKeepsItsPositionAcrossATruncate() throws Exception {
        String stream = "it_stream_" + UUID.randomUUID().toString().substring(0, 8);
        kinesisService.createStream(stream, 1, REGION);
        put(stream, "one");
        put(stream, "two");
        clusterId = "it-mv-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA kds FROM KINESIS REGION '" + REGION + "' IAM_ROLE '" + ROLE_ARN + "'");
            statement.execute("CREATE MATERIALIZED VIEW events_mv AUTO REFRESH NO AS "
                    + "SELECT sequence_number, FROM_VARBYTE(kinesis_data, 'utf-8') AS body FROM kds." + stream);
            assertEquals(List.of(), bodies(statement));

            statement.execute("REFRESH MATERIALIZED VIEW events_mv");
            assertEquals(List.of("one", "two"), bodies(statement));

            put(stream, "three");
            statement.execute("REFRESH MATERIALIZED VIEW events_mv");
            statement.execute("REFRESH MATERIALIZED VIEW events_mv");
            assertEquals(List.of("one", "two", "three"), bodies(statement));

            statement.execute("TRUNCATE events_mv");
            statement.execute("REFRESH MATERIALIZED VIEW events_mv");
            assertEquals(List.of(), bodies(statement));
            put(stream, "four");
            statement.execute("REFRESH MATERIALIZED VIEW events_mv");
            assertEquals(List.of("four"), bodies(statement));

            statement.execute("DROP MATERIALIZED VIEW events_mv");
            SQLException gone = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 FROM events_mv"));
            assertEquals("42P01", gone.getSQLState());
            SQLException unbound = assertThrows(SQLException.class,
                    () -> statement.execute("REFRESH MATERIALIZED VIEW events_mv"));
            assertFalse(unbound.getMessage().isBlank());
        }
    }

    @Test
    @Timeout(180)
    void refusesAutoRefreshYesAndAViewOverAMissingStream() throws Exception {
        clusterId = "it-mv-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA kds FROM KINESIS REGION '" + REGION + "' IAM_ROLE '" + ROLE_ARN + "'");

            SQLException auto = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE MATERIALIZED VIEW v AUTO REFRESH YES AS SELECT * FROM kds.no_such_stream"));
            assertEquals("0A000", auto.getSQLState());
            SQLException missing = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE MATERIALIZED VIEW v AS SELECT * FROM kds.no_such_stream"));
            assertEquals("42P01", missing.getSQLState());
        }
    }
}
