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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.UpdateKinesisStreamingDestinationResponse;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.DynamoDbTableFixtures.createKeyedTable;
import static io.floci.aws.testing.EnforcementFixtures.auth;
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
        tableArn = createKeyedTable(dynamoDb, table);
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
        WriteWindow before = put("before");
        UpdateKinesisStreamingDestinationResponse updated = update(tableArn, "MICROSECOND");
        assertEquals(table, updated.tableName());
        assertEquals(streamArn, updated.streamArn());
        assertEquals("ACTIVE", updated.destinationStatusAsString());
        assertEquals("MICROSECOND",
                updated.updateKinesisStreamingConfiguration().approximateCreationDateTimePrecisionAsString());
        WriteWindow after = put("after");
        assertPrecision("MICROSECOND");
        update(table, "MILLISECOND");
        WriteWindow restored = put("restored");
        assertPrecision("MILLISECOND");

        List<JsonNode> changes = changes();
        assertEquals(List.of("before", "after", "restored"),
                changes.stream().map(n -> n.path("Keys").path("pk").path("S").asText()).toList());
        List<WriteWindow> writes = List.of(before, after, restored);
        for (int index = 0; index < changes.size(); index++) {
            boolean microsecond = index == 1;
            JsonNode change = changes.get(index);
            assertEquals(microsecond ? "MICROSECOND" : "MILLISECOND",
                    change.path("ApproximateCreationDateTimePrecision").asText());
            ChronoUnit unit = microsecond ? ChronoUnit.MICROS : ChronoUnit.MILLIS;
            long timestamp = change.path("ApproximateCreationDateTime").asLong();
            WriteWindow write = writes.get(index);
            assertTrue(timestamp >= unit.between(Instant.EPOCH, write.started())
                    && timestamp <= unit.between(Instant.EPOCH, write.finished()), change.toString());
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
        DynamoDbException invalidUpdate = assertError("ValidationException", () -> update(table, "SECOND"));
        DynamoDbException invalidEnable = assertError("ValidationException",
                () -> dynamoDb.enableKinesisStreamingDestination(b -> b.tableName(table).streamArn(streamArn)
                        .enableKinesisStreamingConfiguration(c -> c.approximateCreationDateTimePrecision("SECOND"))));
        String enumConstraint = "Value 'SECOND' at '%s.approximateCreationDateTimePrecision' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [MILLISECOND, MICROSECOND]";
        assertTrue(invalidUpdate.awsErrorDetails().errorMessage().endsWith(
                enumConstraint.formatted("updateKinesisStreamingConfiguration")));
        assertTrue(invalidEnable.awsErrorDetails().errorMessage().endsWith(
                enumConstraint.formatted("enableKinesisStreamingConfiguration")));
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
            DynamoDbException wrongAccount = assertError("ResourceNotFoundException", () -> update(
                    tableArn.replace(ACCOUNT, "222222222222"), "MICROSECOND"));
            assertEquals("Requested resource not found: Table: " + table + " not found",
                    wrongAccount.awsErrorDetails().errorMessage());
            assertError("ValidationException", () -> update(
                    tableArn.replace("us-east-1", "us-west-2"), "MICROSECOND"));
            DynamoDbException wrongPartition = assertError("ResourceNotFoundException", () -> update(
                    tableArn.replace("arn:aws:", "arn:aws-us-gov:"), "MICROSECOND"));
            assertEquals("Requested resource not found: Table: " + table + " not found",
                    wrongPartition.awsErrorDetails().errorMessage());
        }
        assertPrecision("MILLISECOND");
    }

    @Test
    void jsonAndCborExposeOnlyTheUpdateConfigurationMember() throws Exception {
        Map<String, Object> request = Map.of("TableName", table, "StreamArn", streamArn,
                "UpdateKinesisStreamingConfiguration", Map.of("ApproximateCreationDateTimePrecision", "MICROSECOND"));
        for (String protocol : List.of("json", "cbor", "rpc-v2-cbor")) {
            JsonNode response = invokeUpdate(request, protocol, 200);
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
        for (String protocol : List.of("json", "cbor", "rpc-v2-cbor")) {
            JsonNode error = invokeUpdate(request, protocol, 400);
            assertEquals(code, error.path("__type").asText().replaceFirst(".*#", ""),
                    protocol + ": " + error);
        }
    }

    private JsonNode invokeUpdate(Map<String, Object> request, String protocol, int statusCode) throws Exception {
        boolean cbor = !"json".equals(protocol);
        boolean rpcV2 = "rpc-v2-cbor".equals(protocol);
        ObjectMapper codec = cbor ? new ObjectMapper(new CBORFactory()) : mapper;
        RequestSpecification call = given().header("Authorization", auth(ACCOUNT, "dynamodb"));
        if (rpcV2) {
            call.header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor");
        } else {
            call.header("X-Amz-Target", TARGET)
                    .contentType(cbor ? "application/x-amz-cbor-1.1" : "application/x-amz-json-1.0");
        }
        byte[] body = call.body(codec.writeValueAsBytes(request))
                .post(rpcV2 ? "/service/DynamoDB_20120810/operation/UpdateKinesisStreamingDestination" : "/")
                .then().statusCode(statusCode).extract().asByteArray();
        return codec.readTree(body);
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

    private record WriteWindow(Instant started, Instant finished) {}

    private WriteWindow put(String key) {
        Instant started = Instant.now();
        dynamoDb.putItem(b -> b.tableName(table).item(Map.of("pk", AttributeValue.fromS(key))));
        Instant finished = Instant.now();
        forwarder.awaitIdle(Duration.ofSeconds(5));
        return new WriteWindow(started, finished);
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

    private static DynamoDbException assertError(String code, Executable operation) {
        DynamoDbException error = assertThrows(DynamoDbException.class, operation);
        assertEquals(400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
        return error;
    }
}
