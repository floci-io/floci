package io.floci.aws.services.redshift;

import io.floci.aws.core.common.kafka.KafkaConsumerHandle;
import io.floci.aws.core.common.kafka.KafkaHeaderDto;
import io.floci.aws.core.common.kafka.KafkaOffsetDto;
import io.floci.aws.core.common.kafka.KafkaPartitionOffsets;
import io.floci.aws.core.common.kafka.KafkaRecordDto;
import io.floci.aws.core.common.kafka.KafkaRestClient;
import io.floci.aws.core.common.kafka.KafkaTopicNotFoundException;
import io.floci.aws.core.common.kafka.KarapaceManager;
import io.floci.aws.services.msk.MskService;
import io.floci.aws.services.msk.model.MskCluster;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Streams an MSK topic into a materialized view through a real cluster connection. The MSK cluster is the mock
 * one and the Kafka REST Proxy is scripted, so this covers the SQL side of a refresh (staging table, view,
 * checkpoints, error states); a refresh against a real Redpanda broker is not covered here.
 */
@QuarkusTest
class RedshiftMskStreamingViewDockerNightlyIntegrationTest {
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/MskViewItRole";
    private static final URI REST = URI.create("http://localhost:18082/");
    private static final KafkaConsumerHandle CONSUMER =
            new KafkaConsumerHandle("it-consumer", URI.create("http://localhost:18082/consumers/g/instances/it-consumer"));

    @Inject
    RedshiftService redshiftService;

    @Inject
    MskService mskService;

    @InjectMock
    KarapaceManager karapace;

    @InjectMock
    KafkaRestClient restClient;

    private final List<KafkaRecordDto> topic = new CopyOnWriteArrayList<>();
    private final AtomicLong position = new AtomicLong();
    private String clusterId;
    private MskCluster mskCluster;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift integration tests");
    }

    @BeforeEach
    void scriptTheKafkaRestProxy() {
        mskCluster = mskService.createCluster("it-msk-" + UUID.randomUUID().toString().substring(0, 8));
        when(karapace.ensureStarted(anyString())).thenReturn(REST);
        when(restClient.listPartitions(eq(REST), anyString())).thenReturn(List.of(0));
        when(restClient.partitionOffsets(eq(REST), anyString(), anyInt()))
                .thenAnswer(invocation -> new KafkaPartitionOffsets(0, topic.size()));
        when(restClient.createConsumer(eq(REST), anyString(), anyString())).thenReturn(CONSUMER);
        doAnswer(invocation -> {
            position.set(0);
            return null;
        }).when(restClient).seekToBeginning(any(), anyList());
        doAnswer(invocation -> {
            List<KafkaOffsetDto> offsets = invocation.getArgument(1);
            if (!offsets.isEmpty()) {
                position.set(offsets.get(0).offset());
            }
            return null;
        }).when(restClient).seek(any(), anyList());
        when(restClient.poll(eq(CONSUMER), any())).thenAnswer(invocation -> {
            List<KafkaRecordDto> unread = new ArrayList<>();
            for (long offset = position.get(); offset < topic.size(); offset++) {
                unread.add(topic.get((int) offset));
            }
            position.set(topic.size());
            return unread;
        });
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        mskService.deleteCluster(mskCluster.getClusterArn());
    }

    private void produce(String value, String... headers) {
        List<KafkaHeaderDto> kafkaHeaders = new ArrayList<>();
        for (int i = 0; i < headers.length; i += 2) {
            kafkaHeaders.add(new KafkaHeaderDto(headers[i], headers[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        long offset = topic.size();
        topic.add(new KafkaRecordDto("orders", 0, offset, 1_700_000_000_000L + offset, "CreateTime",
                ("k" + offset).getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8), kafkaHeaders));
    }

    private static List<String> rows(Statement statement) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery("SELECT kafka_partition, kafka_offset, kafka_timestamp_type, "
                + "k, body, trace FROM orders_mv ORDER BY kafka_offset")) {
            while (result.next()) {
                rows.add(result.getLong(1) + ":" + result.getLong(2) + ":" + result.getString(3) + ":"
                        + result.getString(4) + ":" + result.getString(5) + ":" + result.getString(6));
            }
        }
        return rows;
    }

    private void createSchemaAndView(Statement statement) throws SQLException {
        statement.execute("CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE_ARN
                + "' AUTHENTICATION iam CLUSTER_ARN '" + mskCluster.getClusterArn() + "'");
        statement.execute("CREATE MATERIALIZED VIEW orders_mv AUTO REFRESH NO AS "
                + "SELECT kafka_partition, kafka_offset, kafka_timestamp_type, FROM_VARBYTE(kafka_key, 'utf-8') AS k, "
                + "FROM_VARBYTE(kafka_value, 'utf-8') AS body, kafka_headers->>'trace' AS trace FROM msk.orders");
    }

    @Test
    @Timeout(180)
    void refreshLoadsOnlyWhatTheTopicGainedAndKeepsItsPositionAcrossATruncate() throws Exception {
        produce("one", "trace", "t1");
        produce("two");
        clusterId = "it-mv-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            createSchemaAndView(statement);
            assertEquals(List.of(), rows(statement));

            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            assertEquals(List.of("0:0:C:k0:one:t1", "0:1:C:k1:two:null"), rows(statement));

            produce("three");
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            assertEquals(List.of("0:0:C:k0:one:t1", "0:1:C:k1:two:null", "0:2:C:k2:three:null"), rows(statement));

            statement.execute("TRUNCATE orders_mv");
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            assertEquals(List.of(), rows(statement));
            produce("four");
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            assertEquals(List.of("0:3:C:k3:four:null"), rows(statement));

            statement.execute("DROP MATERIALIZED VIEW orders_mv");
            SQLException gone = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 FROM orders_mv"));
            assertEquals("42P01", gone.getSQLState());
            statement.execute("DROP SCHEMA msk");
        }
    }

    @Test
    @Timeout(180)
    void aBrokerThatIsDownFailsTheRefreshAndTheViewKeepsItsRows() throws Exception {
        produce("one");
        clusterId = "it-mv-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            createSchemaAndView(statement);
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            produce("two");
            when(restClient.listPartitions(eq(REST), anyString())).thenThrow(new UncheckedIOException(new IOException("refused")));

            SQLException down = assertThrows(SQLException.class, () -> statement.execute("REFRESH MATERIALIZED VIEW orders_mv"));

            assertEquals("58030", down.getSQLState());
            assertEquals(List.of("0:0:C:k0:one:null"), rows(statement));
            when(restClient.listPartitions(eq(REST), anyString())).thenReturn(List.of(0));
            statement.execute("REFRESH MATERIALIZED VIEW orders_mv");
            assertEquals(List.of("0:0:C:k0:one:null", "0:1:C:k1:two:null"), rows(statement));
        }
    }

    @Test
    @Timeout(180)
    void refusesAutoRefreshYesAndAViewOverAMissingTopic() throws Exception {
        clusterId = "it-mv-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE_ARN
                    + "' AUTHENTICATION iam CLUSTER_ARN '" + mskCluster.getClusterArn() + "'");

            SQLException auto = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE MATERIALIZED VIEW v AUTO REFRESH YES AS SELECT * FROM msk.orders"));
            assertEquals("0A000", auto.getSQLState());

            when(restClient.listPartitions(eq(REST), anyString())).thenThrow(new KafkaTopicNotFoundException(
                    "no_such_topic", new RuntimeException("404")));
            SQLException missing = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE MATERIALIZED VIEW v AS SELECT * FROM msk.no_such_topic"));
            assertEquals("42P01", missing.getSQLState());
        }
    }
}
