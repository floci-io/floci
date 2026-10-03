package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ApproximateCreationDateTimePrecision;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeKinesisStreamingDestinationRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeKinesisStreamingDestinationResponse;
import software.amazon.awssdk.services.dynamodb.model.DestinationStatus;
import software.amazon.awssdk.services.dynamodb.model.DisableKinesisStreamingDestinationRequest;
import software.amazon.awssdk.services.dynamodb.model.DisableKinesisStreamingDestinationResponse;
import software.amazon.awssdk.services.dynamodb.model.EnableKinesisStreamingConfiguration;
import software.amazon.awssdk.services.dynamodb.model.EnableKinesisStreamingDestinationRequest;
import software.amazon.awssdk.services.dynamodb.model.EnableKinesisStreamingDestinationResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.KinesisDataStreamDestination;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamSummaryRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the Kinesis streaming destination responses back through the AWS SDK, which parses them
 * into typed models: a status outside the enum lands as UNKNOWN_TO_SDK_VERSION, and a missing
 * EnableKinesisStreamingConfiguration lands as null. Handcrafted HTTP cannot show either.
 *
 * <p>Floci used to ignore ApproximateCreationDateTimePrecision and always report MILLISECOND.
 */
@DisplayName("DynamoDB Kinesis Streaming Destination")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbKinesisStreamingDestinationTest {

    private static final String TABLE_NAME = "sdk-kinesis-destination-table";
    private static final String STREAM_NAME = "sdk-kinesis-destination-stream";

    private static DynamoDbClient ddb;
    private static KinesisClient kinesis;
    private static String streamArn;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        kinesis = TestFixtures.kinesisClient();

        kinesis.createStream(CreateStreamRequest.builder()
                .streamName(STREAM_NAME)
                .shardCount(1)
                .build());
        streamArn = kinesis.describeStreamSummary(DescribeStreamSummaryRequest.builder()
                        .streamName(STREAM_NAME)
                        .build())
                .streamDescriptionSummary()
                .streamARN();

        ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk")
                        .attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    @AfterAll
    static void cleanup() {
        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (Exception ignored) {
            // The table may already be gone; cleanup must not mask a test failure.
        }
        try {
            kinesis.deleteStream(DeleteStreamRequest.builder().streamName(STREAM_NAME).build());
        } catch (Exception ignored) {
            // Same for the stream.
        }
        if (ddb != null) {
            ddb.close();
        }
        if (kinesis != null) {
            kinesis.close();
        }
    }

    @Test
    @Order(1)
    void enableEchoesTheRequestedPrecision() {
        EnableKinesisStreamingDestinationResponse response = ddb.enableKinesisStreamingDestination(
                EnableKinesisStreamingDestinationRequest.builder()
                        .tableName(TABLE_NAME)
                        .streamArn(streamArn)
                        .enableKinesisStreamingConfiguration(EnableKinesisStreamingConfiguration.builder()
                                .approximateCreationDateTimePrecision(
                                        ApproximateCreationDateTimePrecision.MICROSECOND)
                                .build())
                        .build());

        assertThat(response.tableName()).isEqualTo(TABLE_NAME);
        assertThat(response.streamArn()).isEqualTo(streamArn);
        assertThat(response.destinationStatus())
                .as("a status the SDK does not know would be UNKNOWN_TO_SDK_VERSION")
                .isEqualTo(DestinationStatus.ACTIVE);
        assertThat(response.enableKinesisStreamingConfiguration())
                .as("the response must carry the configuration, not just the status")
                .isNotNull();
        assertThat(response.enableKinesisStreamingConfiguration().approximateCreationDateTimePrecision())
                .isEqualTo(ApproximateCreationDateTimePrecision.MICROSECOND);
    }

    @Test
    @Order(2)
    void describeReportsTheStoredPrecision() {
        DescribeKinesisStreamingDestinationResponse response = ddb.describeKinesisStreamingDestination(
                DescribeKinesisStreamingDestinationRequest.builder().tableName(TABLE_NAME).build());

        assertThat(response.tableName()).isEqualTo(TABLE_NAME);
        assertThat(response.kinesisDataStreamDestinations()).hasSize(1);

        KinesisDataStreamDestination destination = response.kinesisDataStreamDestinations().get(0);
        assertThat(destination.streamArn()).isEqualTo(streamArn);
        assertThat(destination.destinationStatus()).isEqualTo(DestinationStatus.ACTIVE);
        assertThat(destination.destinationStatusDescription()).isNotBlank();
        assertThat(destination.approximateCreationDateTimePrecision())
                .as("the precision the destination was enabled with, not the MILLISECOND default")
                .isEqualTo(ApproximateCreationDateTimePrecision.MICROSECOND);
    }

    @Test
    @Order(3)
    void disableReportsTheStoredPrecision() {
        DisableKinesisStreamingDestinationResponse response = ddb.disableKinesisStreamingDestination(
                DisableKinesisStreamingDestinationRequest.builder()
                        .tableName(TABLE_NAME)
                        .streamArn(streamArn)
                        .build());

        assertThat(response.tableName()).isEqualTo(TABLE_NAME);
        assertThat(response.streamArn()).isEqualTo(streamArn);
        assertThat(response.destinationStatus()).isEqualTo(DestinationStatus.DISABLED);
        assertThat(response.enableKinesisStreamingConfiguration())
                .as("AWS answers Disable with the destination's configuration as well")
                .isNotNull();
        assertThat(response.enableKinesisStreamingConfiguration().approximateCreationDateTimePrecision())
                .isEqualTo(ApproximateCreationDateTimePrecision.MICROSECOND);
    }
}
