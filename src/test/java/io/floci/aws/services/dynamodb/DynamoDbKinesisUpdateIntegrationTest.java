package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateKinesisStreamingDestinationResponse;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbKinesisUpdateIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String TARGET = "DynamoDB_20120810.UpdateKinesisStreamingDestination";
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    KinesisStreamingForwarder forwarder;

    private DynamoDbClient dynamoDb;
    private KinesisClient kinesis;
    private String table;
    private String tableArn;
    private String streamArn;

    @BeforeEach
    void createDestination() {
        dynamoDb = AwsTestClients.create(DynamoDbClient.builder(), ACCOUNT, Region.US_EAST_1);
        kinesis = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1);
        table = "kinesis-update-" + UUID.randomUUID();
        kinesis.createStream(b -> b.streamName(table).shardCount(1));
        streamArn = kinesis.describeStreamSummary(b -> b.streamName(table)).streamDescriptionSummary().streamARN();
        tableArn = dynamoDb.createTable(b -> b.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build()))
                .tableDescription().tableArn();
        dynamoDb.enableKinesisStreamingDestination(b -> b.tableName(table).streamArn(streamArn));
    }

    @AfterEach
    void cleanUp() {
        try (DynamoDbClient dynamoDbClient = dynamoDb; KinesisClient kinesisClient = kinesis) {
            try {
                dynamoDbClient.deleteTable(b -> b.tableName(table));
            } finally {
                kinesisClient.deleteStream(b -> b.streamName(table));
            }
        }
    }

    @Test
    void sdkUpdateChangesDeliveredRecordPrecisionAndPreservesTheDestination() throws Exception {
        long earliestMillis = Instant.now().toEpochMilli();
        put("before");
        UpdateKinesisStreamingDestinationResponse updated = update(tableArn, "MICROSECOND");
        assertEquals(table, updated.tableName());
        assertEquals(streamArn, updated.streamArn());
        assertEquals("ACTIVE", updated.destinationStatusAsString());
        assertEquals("MICROSECOND",
                updated.updateKinesisStreamingConfiguration().approximateCreationDateTimePrecisionAsString());
        put("after");
        assertPrecision("MICROSECOND");
        update(table, "MILLISECOND");
        put("restored");
        assertPrecision("MILLISECOND");

        List<JsonNode> changes = changes();
        assertEquals(List.of("before", "after", "restored"),
                changes.stream().map(n -> n.path("Keys").path("pk").path("S").asText()).toList());
        long latestMillis = Instant.now().toEpochMilli();
        for (int index = 0; index < changes.size(); index++) {
            boolean microsecond = index == 1;
            JsonNode change = changes.get(index);
            assertEquals(microsecond ? "MICROSECOND" : "MILLISECOND",
                    change.path("ApproximateCreationDateTimePrecision").asText());
            long timestampMillis = change.path("ApproximateCreationDateTime").asLong() / (microsecond ? 1000 : 1);
            assertTrue(timestampMillis >= earliestMillis && timestampMillis <= latestMillis, change.toString());
        }
    }

    @Test
    void omittedAndEmptyConfigurationRetainTheCurrentPrecision() {
        update(table, "MICROSECOND");
        UpdateKinesisStreamingDestinationResponse omitted = dynamoDb.updateKinesisStreamingDestination(
                b -> b.tableName(table).streamArn(streamArn));
        assertEquals("MICROSECOND",
                omitted.updateKinesisStreamingConfiguration().approximateCreationDateTimePrecisionAsString());
        UpdateKinesisStreamingDestinationResponse empty = dynamoDb.updateKinesisStreamingDestination(
                b -> b.tableName(table).streamArn(streamArn).updateKinesisStreamingConfiguration(c -> { }));
        assertEquals("MICROSECOND",
                empty.updateKinesisStreamingConfiguration().approximateCreationDateTimePrecisionAsString());
        assertPrecision("MICROSECOND");
    }

    @Test
    void invalidPrecisionMissingResourcesAndDisabledDestinationLeaveStateIntact() {
        assertError("ValidationException", () -> update(table, "SECOND"));
        assertPrecision("MILLISECOND");
        assertError("ResourceNotFoundException", () -> update(table + "-missing", "MICROSECOND"));
        assertError("ResourceNotFoundException", () -> dynamoDb.updateKinesisStreamingDestination(
                b -> b.tableName(table).streamArn(streamArn + "-missing")
                        .updateKinesisStreamingConfiguration(c -> c.approximateCreationDateTimePrecision("MICROSECOND"))));
        assertPrecision("MILLISECOND");
        dynamoDb.disableKinesisStreamingDestination(b -> b.tableName(table).streamArn(streamArn));
        assertError("ValidationException", () -> update(table, "MICROSECOND"));
        assertEquals("DISABLED", dynamoDb.describeKinesisStreamingDestination(b -> b.tableName(table))
                .kinesisDataStreamDestinations().getFirst().destinationStatusAsString());
        assertPrecision("MILLISECOND");
    }

    @Test
    void updatesCannotCrossAccountRegionOrArnOwnership() {
        try (DynamoDbClient foreignAccount = AwsTestClients.create(
                DynamoDbClient.builder(), "222222222222", Region.US_EAST_1);
             DynamoDbClient foreignRegion = AwsTestClients.create(
                     DynamoDbClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            assertError("ResourceNotFoundException", () -> foreignAccount.updateKinesisStreamingDestination(
                    b -> b.tableName(table).streamArn(streamArn)));
            assertError("ResourceNotFoundException", () -> foreignRegion.updateKinesisStreamingDestination(
                    b -> b.tableName(table).streamArn(streamArn)));
            assertError("ResourceNotFoundException", () -> update(
                    tableArn.replace(ACCOUNT, "222222222222"), "MICROSECOND"));
            assertError("ValidationException", () -> update(
                    tableArn.replace("us-east-1", "us-west-2"), "MICROSECOND"));
            assertError("ResourceNotFoundException", () -> update(
                    tableArn.replace("arn:aws:", "arn:aws-us-gov:"), "MICROSECOND"));
        }
        assertPrecision("MILLISECOND");
    }

    @Test
    void jsonAndCborExposeOnlyTheUpdateConfigurationMember() throws Exception {
        Map<String, Object> request = Map.of("TableName", table, "StreamArn", streamArn,
                "UpdateKinesisStreamingConfiguration", Map.of("ApproximateCreationDateTimePrecision", "MICROSECOND"));
        for (String protocol : List.of("json", "cbor", "rpc-v2-cbor")) {
            boolean cbor = !"json".equals(protocol);
            boolean rpcV2 = "rpc-v2-cbor".equals(protocol);
            ObjectMapper codec = cbor ? new ObjectMapper(new CBORFactory()) : mapper;
            RequestSpecification call = given()
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                            + "/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=test");
            if (rpcV2) {
                call.header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor");
            } else {
                call.header("X-Amz-Target", TARGET)
                        .contentType(cbor ? "application/x-amz-cbor-1.1" : "application/x-amz-json-1.0");
            }
            byte[] body = call.body(codec.writeValueAsBytes(request))
                    .post(rpcV2 ? "/service/DynamoDB_20120810/operation/UpdateKinesisStreamingDestination" : "/")
                    .then().statusCode(200).extract().asByteArray();
            JsonNode response = codec.readTree(body);
            assertEquals(table, response.path("TableName").asText());
            assertEquals(streamArn, response.path("StreamArn").asText());
            assertEquals("MICROSECOND", response.path("UpdateKinesisStreamingConfiguration")
                    .path("ApproximateCreationDateTimePrecision").asText());
            assertFalse(response.has("EnableKinesisStreamingConfiguration"), response.toString());
            assertFalse(response.has("DestinationStatusDescription"), response.toString());
        }
        assertPrecision("MICROSECOND");
    }

    @Test
    void requiredMembersAndConfigurationShapeAreValidatedBeforeMutation() throws Exception {
        for (Map<String, Object> request : List.<Map<String, Object>>of(
                Map.of("StreamArn", streamArn),
                Map.of("TableName", table),
                Map.of("TableName", table, "StreamArn", ""))) {
            assertInvalidRequest(request, "ValidationException");
        }
        for (Map<String, Object> request : List.<Map<String, Object>>of(
                Map.of("TableName", 123, "StreamArn", streamArn),
                Map.of("TableName", table, "StreamArn", 123),
                Map.of("TableName", table, "StreamArn", streamArn, "UpdateKinesisStreamingConfiguration", "MICROSECOND"),
                Map.of("TableName", table, "StreamArn", streamArn,
                        "UpdateKinesisStreamingConfiguration", Map.of("ApproximateCreationDateTimePrecision", 123)),
                Map.of("TableName", table, "StreamArn", streamArn,
                        "UpdateKinesisStreamingConfiguration", Map.of("ApproximateCreationDateTimePrecision", true)),
                Map.of("TableName", table, "StreamArn", streamArn,
                        "UpdateKinesisStreamingConfiguration", Map.of("ApproximateCreationDateTimePrecision", Map.of())))) {
            assertInvalidRequest(request, "SerializationException");
        }
        assertPrecision("MILLISECOND");
    }

    private void assertInvalidRequest(Map<String, Object> request, String code) throws Exception {
        byte[] body = given().header("X-Amz-Target", TARGET)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=test")
                .contentType("application/x-amz-json-1.0").body(mapper.writeValueAsBytes(request))
                .post("/").then().statusCode(400).extract().asByteArray();
        JsonNode error = mapper.readTree(body);
        assertEquals(code, error.path("__type").asText().replaceFirst(".*#", ""), error.toString());
    }

    private UpdateKinesisStreamingDestinationResponse update(String tableIdentifier, String precision) {
        return dynamoDb.updateKinesisStreamingDestination(b -> b.tableName(tableIdentifier).streamArn(streamArn)
                .updateKinesisStreamingConfiguration(c -> c.approximateCreationDateTimePrecision(precision)));
    }

    private void assertPrecision(String precision) {
        assertEquals(1, dynamoDb.describeKinesisStreamingDestination(b -> b.tableName(table))
                .kinesisDataStreamDestinations().size());
        assertEquals(precision, dynamoDb.describeKinesisStreamingDestination(b -> b.tableName(table))
                .kinesisDataStreamDestinations().getFirst().approximateCreationDateTimePrecisionAsString());
    }

    private void put(String key) {
        dynamoDb.putItem(b -> b.tableName(table).item(Map.of("pk", AttributeValue.fromS(key))));
        forwarder.awaitIdle(Duration.ofSeconds(5));
    }

    private List<JsonNode> changes() throws Exception {
        String shardId = kinesis.describeStream(b -> b.streamName(table)).streamDescription().shards().getFirst().shardId();
        String iterator = kinesis.getShardIterator(b -> b.streamName(table).shardId(shardId)
                .shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
        List<JsonNode> changes = new ArrayList<>();
        for (Record record : kinesis.getRecords(b -> b.shardIterator(iterator)).records()) {
            changes.add(mapper.readTree(record.data().asByteArray()).path("dynamodb"));
        }
        return changes;
    }

    private static void assertError(String code, Executable operation) {
        DynamoDbException error = assertThrows(DynamoDbException.class, operation);
        assertEquals(400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }
}
