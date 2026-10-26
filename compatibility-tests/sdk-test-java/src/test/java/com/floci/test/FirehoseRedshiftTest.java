package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.BufferingHints;
import software.amazon.awssdk.services.firehose.model.CopyCommand;
import software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeleteDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DescribeDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DestinationDescription;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.firehose.model.RedshiftDestinationConfiguration;
import software.amazon.awssdk.services.firehose.model.ResourceNotFoundException;
import software.amazon.awssdk.services.firehose.model.S3DestinationConfiguration;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.ClusterNotFoundException;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultRequest;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Firehose Redshift destination")
class FirehoseRedshiftTest {

    private static final String STREAM = "sdk-rs-shape-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String JDBC_URL = "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose";
    private static final int BUFFERING_INTERVAL_SECONDS = 5;
    private static final long POLL_TIMEOUT_MILLIS = 150_000L;
    private static final long POLL_INTERVAL_MILLIS = TestFixtures.isRealAws() ? 5_000L : 250L;
    private static FirehoseClient firehose;

    @BeforeAll
    static void setUp() {
        firehose = TestFixtures.firehoseClient();
    }

    @AfterAll
    static void tearDown() {
        if (firehose != null) {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder().deliveryStreamName(STREAM).build());
            } catch (ResourceNotFoundException ignored) {
                // The stream does not exist when a test failed before creating it.
            }
            firehose.close();
        }
    }

    @Test
    @DisplayName("CreateDeliveryStream stores the Redshift destination and DescribeDeliveryStream returns it")
    void createAndDescribe() {
        firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM)
                .redshiftDestinationConfiguration(RedshiftDestinationConfiguration.builder()
                        .roleARN(ROLE_ARN)
                        .clusterJDBCURL(JDBC_URL)
                        .username("admin")
                        .password("Secret123")
                        .copyCommand(CopyCommand.builder().dataTableName("events").copyOptions("json 'auto'").build())
                        .s3Configuration(S3DestinationConfiguration.builder()
                                .roleARN(ROLE_ARN)
                                .bucketARN("arn:aws:s3:::sdk-rs-stage")
                                .build())
                        .build())
                .build());

        DestinationDescription destination = firehose.describeDeliveryStream(
                        DescribeDeliveryStreamRequest.builder().deliveryStreamName(STREAM).build())
                .deliveryStreamDescription().destinations().get(0);

        assertThat(destination.redshiftDestinationDescription().clusterJDBCURL()).isEqualTo(JDBC_URL);
        assertThat(destination.redshiftDestinationDescription().username()).isEqualTo("admin");
        assertThat(destination.redshiftDestinationDescription().copyCommand().dataTableName()).isEqualTo("events");
        assertThat(destination.redshiftDestinationDescription().s3DestinationDescription().bucketARN())
                .isEqualTo("arn:aws:s3:::sdk-rs-stage");
    }

    @Test
    @DisplayName("PutRecord is loaded into the Redshift table through COPY")
    void deliversIntoTheRedshiftTable() throws Exception {
        String clusterId = TestFixtures.uniqueName("fh-rs-cluster");
        String stream = TestFixtures.uniqueName("fh-rs-delivery");
        String table = "fh_events";
        RedshiftClient redshift = TestFixtures.redshiftClient();
        RedshiftDataClient data = TestFixtures.redshiftDataClient();
        try {
            redshift.createCluster(CreateClusterRequest.builder()
                    .clusterIdentifier(clusterId)
                    .nodeType("dc2.large")
                    .masterUsername("admin")
                    .masterUserPassword("password123")
                    .build());
            awaitFinished(data, execute(data, clusterId, "CREATE TABLE " + table + " (id int, name varchar(20))"));

            firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                    .deliveryStreamName(stream)
                    .redshiftDestinationConfiguration(RedshiftDestinationConfiguration.builder()
                            .roleARN(ROLE_ARN)
                            .clusterJDBCURL("jdbc:redshift://" + clusterId
                                    + ".abc123.us-east-1.redshift.amazonaws.com:5439/dev")
                            .username("admin")
                            .password("password123")
                            .copyCommand(CopyCommand.builder()
                                    .dataTableName(table).copyOptions("json 'auto'").build())
                            .s3Configuration(S3DestinationConfiguration.builder()
                                    .roleARN(ROLE_ARN)
                                    .bucketARN("arn:aws:s3:::" + stream + "-stage")
                                    .bufferingHints(BufferingHints.builder()
                                            .intervalInSeconds(BUFFERING_INTERVAL_SECONDS).sizeInMBs(1).build())
                                    .build())
                            .build())
                    .build());
            firehose.putRecord(PutRecordRequest.builder()
                    .deliveryStreamName(stream)
                    .record(Record.builder().data(SdkBytes.fromString("{\"id\":1,\"name\":\"alice\"}",
                            StandardCharsets.UTF_8)).build())
                    .build());

            Instant deadline = Instant.now().plus(Duration.ofMillis(POLL_TIMEOUT_MILLIS));
            long rows = 0;
            while (rows == 0 && Instant.now().isBefore(deadline)) {
                String id = awaitFinished(data, execute(data, clusterId, "SELECT count(*) FROM " + table));
                rows = data.getStatementResult(GetStatementResultRequest.builder().id(id).build())
                        .records().get(0).get(0).longValue();
                if (rows == 0) {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                }
            }
            assertThat(rows).isEqualTo(1L);
        } finally {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder().deliveryStreamName(stream).build());
            } catch (ResourceNotFoundException ignored) {
                // The stream does not exist when creation failed.
            }
            try {
                redshift.deleteCluster(DeleteClusterRequest.builder().clusterIdentifier(clusterId)
                        .skipFinalClusterSnapshot(true).build());
            } catch (ClusterNotFoundException ignored) {
                // The cluster does not exist when creation failed.
            }
            data.close();
            redshift.close();
        }
    }

    private static String execute(RedshiftDataClient data, String clusterId, String sql) {
        return data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .dbUser("admin")
                .database("dev")
                .sql(sql)
                .build()).id();
    }

    private static String awaitFinished(RedshiftDataClient data, String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            DescribeStatementResponse describe = data.describeStatement(DescribeStatementRequest.builder().id(id).build());
            if (describe.status() == StatusString.FINISHED) {
                return id;
            }
            if (describe.status() == StatusString.FAILED || describe.status() == StatusString.ABORTED) {
                throw new IllegalStateException("Statement " + id + " ended " + describe.status() + ": " + describe.error());
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Statement " + id + " did not finish in time");
    }
}
