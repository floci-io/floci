package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Cursor;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Position;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.RecordsPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Shard;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Stream;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DynamoDB API served by an external DynamoDB Local, reached at FLOCI_SERVICES_DYNAMODB_LOCAL_ENDPOINT. */
@QuarkusTest
@TestProfile(DynamoDbLocalBackendIntegrationTest.LocalProfile.class)
@EnabledIfEnvironmentVariable(named = DynamoDbLocalBackendIntegrationTest.ENDPOINT_VARIABLE, matches = ".+")
class DynamoDbLocalBackendIntegrationTest {

    static final String ENDPOINT_VARIABLE = "FLOCI_SERVICES_DYNAMODB_LOCAL_ENDPOINT";

    public static final class LocalProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String endpoint = System.getenv(ENDPOINT_VARIABLE);
            return Map.of("floci.services.dynamodb.backend", "local",
                    "floci.services.dynamodb.local-endpoint", endpoint == null ? "" : endpoint);
        }
    }

    private record Caller(String account, String region) {
        String authorization() {
            return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + region
                    + "/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";
        }
    }

    private record CreatedTable(Caller caller, String name) {}

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ACCOUNT_A = randomAccount();
    private static final String ACCOUNT_B = otherAccountThan(ACCOUNT_A);
    private static final Caller A_EAST = new Caller(ACCOUNT_A, "us-east-1");
    private static final Caller A_WEST = new Caller(ACCOUNT_A, "eu-west-1");
    private static final Caller B_EAST = new Caller(ACCOUNT_B, "us-east-1");
    private static final String STREAMED = ", \"StreamSpecification\": {\"StreamEnabled\": true, "
            + "\"StreamViewType\": \"NEW_AND_OLD_IMAGES\"}";

    @Inject
    DynamoDbStreamReader streamReader;

    @Inject
    DynamoDbFacade dynamoDbFacade;

    private final List<CreatedTable> created = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteCreatedTables() {
        for (CreatedTable table : created) {
            dynamoDb(table.caller(), "DeleteTable", "{\"TableName\": \"" + table.name() + "\"}").statusCode(200);
        }
        created.clear();
    }

    @Test
    void theSameTableNameIsIsolatedAcrossAccountsAndRegions() {
        String table = tableName("isolation");
        for (Caller caller : List.of(A_EAST, B_EAST, A_WEST)) {
            createTable(caller, table, "").statusCode(200);
        }
        putItem(A_EAST, table, "only-in-a-east");

        scanCount(A_EAST, table).body("Count", equalTo(1));
        scanCount(B_EAST, table).body("Count", equalTo(0));
        scanCount(A_WEST, table).body("Count", equalTo(0));
        dynamoDb(B_EAST, "GetItem", "{\"TableName\": \"" + table + "\", \"Key\": {\"pk\": {\"S\": \"only-in-a-east\"}}}")
            .statusCode(200)
            .body("Item", nullValue());
    }

    @Test
    void tableArnsArePublicAndAcceptedAsTableNames() {
        String table = tableName("arn");
        String arn = tableArn(A_WEST, table);

        createTable(A_WEST, table, "").statusCode(200).body("TableDescription.TableArn", equalTo(arn));

        dynamoDb(A_WEST, "DescribeTable", "{\"TableName\": \"" + arn + "\"}")
            .statusCode(200)
            .body("Table.TableName", equalTo(table))
            .body("Table.TableArn", equalTo(arn));
    }

    @Test
    void tagsRoundTripAlongsideCreateTableTags() {
        String table = tableName("tags");
        String arn = tableArn(B_EAST, table);
        createTable(B_EAST, table, ", \"Tags\": [{\"Key\": \"env\", \"Value\": \"test\"}]").statusCode(200);

        dynamoDb(B_EAST, "TagResource",
                "{\"ResourceArn\": \"" + arn + "\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"data\"}]}")
            .statusCode(200);
        dynamoDb(B_EAST, "ListTagsOfResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .statusCode(200)
            .body("Tags.Key", containsInAnyOrder("env", "team"));

        dynamoDb(B_EAST, "UntagResource", "{\"ResourceArn\": \"" + arn + "\", \"TagKeys\": [\"env\"]}")
            .statusCode(200);
        dynamoDb(B_EAST, "ListTagsOfResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .statusCode(200)
            .body("Tags.Key", contains("team"))
            .body("Tags.Value", contains("data"));
    }

    @Test
    void listingTagsOfAMissingTableIsAccessDenied() {
        dynamoDb(B_EAST, "ListTagsOfResource", "{\"ResourceArn\": \"" + tableArn(B_EAST, tableName("absent")) + "\"}")
            .statusCode(400)
            .body("__type", endsWith("AccessDeniedException"));
    }

    @Test
    void streamRecordsCarryPublicArnsAndTheCallerRegion() {
        String table = tableName("stream");
        String streamArn = streamedTableWithOneItem(A_WEST, table);
        assertTrue(streamArn.startsWith(tableArn(A_WEST, table) + "/stream/"), streamArn);

        streams(A_WEST, "ListStreams", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("Streams.StreamArn", contains(streamArn));
        String shardId = streams(A_WEST, "DescribeStream", "{\"StreamArn\": \"" + streamArn + "\"}")
            .statusCode(200)
            .body("StreamDescription.StreamArn", equalTo(streamArn))
            .extract().path("StreamDescription.Shards[0].ShardId");
        String iterator = streams(A_WEST, "GetShardIterator", "{\"StreamArn\": \"" + streamArn
                + "\", \"ShardId\": \"" + shardId + "\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
            .statusCode(200)
            .extract().path("ShardIterator");

        streams(A_WEST, "GetRecords", "{\"ShardIterator\": \"" + iterator + "\"}")
            .statusCode(200)
            .body("Records", hasSize(1))
            .body("Records[0].eventName", equalTo("INSERT"))
            .body("Records[0].awsRegion", equalTo("eu-west-1"))
            .body("Records[0].dynamodb.Keys.pk.S", equalTo("streamed"));
    }

    @Test
    void aStreamOfAnotherAccountIsNotFound() {
        String table = tableName("foreign-stream");
        String streamArn = streamedTableWithOneItem(A_EAST, table);

        streams(B_EAST, "DescribeStream", "{\"StreamArn\": \"" + streamArn + "\"}")
            .statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));
    }

    @Test
    void theStreamReaderReadsTheSameRecordsWithStreamCheckpoints() {
        String table = tableName("reader");
        String streamArn = streamedTableWithOneItem(A_WEST, table);

        Stream stream = Stream.of(streamArn);
        List<Shard> shards = streamReader.shards(stream);
        Cursor cursor = streamReader.getShardIterator(stream, shards.get(0).shardId(), Position.TRIM_HORIZON, null);
        RecordsPage page = streamReader.getRecords(cursor, 10);

        assertEquals(1, page.records().size());
        JsonNode record = page.records().get(0).awsRecord();
        assertEquals("INSERT", record.path("eventName").asText());
        assertEquals("eu-west-1", record.path("awsRegion").asText());
        assertEquals("streamed", record.path("dynamodb").path("Keys").path("pk").path("S").asText());
        assertEquals(CheckpointLifetime.STREAM, streamReader.checkpointLifetime());
    }

    @Test
    void theDirectDeleteRemovesAProtectedTable() {
        String table = tableName("protected");
        createTable(A_WEST, table, ", \"DeletionProtectionEnabled\": true").statusCode(200);
        dynamoDb(A_WEST, "DeleteTable", "{\"TableName\": \"" + table + "\"}").statusCode(400);

        dynamoDbFacade.tables().deleteTable(new Scope(A_WEST.account(), A_WEST.region()), table);
        created.clear();

        dynamoDb(A_WEST, "DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));
    }

    @Test
    void resetIsRefused() {
        given().when().post("/_floci/state/reset").then().statusCode(409);
    }

    @Test
    void infoReportsTheLocalBackend() {
        given()
            .when().get("/_floci/info")
            .then()
                .statusCode(200)
                .body("dynamodb_backend", equalTo("local"));
    }

    private String streamedTableWithOneItem(Caller caller, String table) {
        String streamArn = createTable(caller, table, STREAMED)
            .statusCode(200)
            .extract().path("TableDescription.LatestStreamArn");
        putItem(caller, table, "streamed");
        return streamArn;
    }

    private ValidatableResponse createTable(Caller caller, String table, String extraFields) {
        ValidatableResponse response = dynamoDb(caller, "CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST"%s
            }
            """.formatted(table, extraFields));
        created.add(new CreatedTable(caller, table));
        return response;
    }

    private static void putItem(Caller caller, String table, String pk) {
        dynamoDb(caller, "PutItem", "{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"" + pk + "\"}}}")
            .statusCode(200);
    }

    private static ValidatableResponse scanCount(Caller caller, String table) {
        return dynamoDb(caller, "Scan", "{\"TableName\": \"" + table + "\"}").statusCode(200);
    }

    private static ValidatableResponse dynamoDb(Caller caller, String action, String body) {
        return send(caller, "DynamoDB_20120810." + action, body);
    }

    private static ValidatableResponse streams(Caller caller, String action, String body) {
        return send(caller, "DynamoDBStreams_20120810." + action, body);
    }

    private static ValidatableResponse send(Caller caller, String target, String body) {
        return given()
            .header("X-Amz-Target", target)
            .header("Authorization", caller.authorization())
            .contentType(CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static String tableArn(Caller caller, String table) {
        return "arn:aws:dynamodb:" + caller.region() + ":" + caller.account() + ":table/" + table;
    }

    private static String tableName(String purpose) {
        return "local-it-" + purpose + "-" + UUID.randomUUID();
    }

    private static String randomAccount() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
    }

    private static String otherAccountThan(String account) {
        String other = randomAccount();
        while (other.equals(account)) {
            other = randomAccount();
        }
        return other;
    }
}
