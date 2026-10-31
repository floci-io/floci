package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbBatchWriteLimitIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20120810", "20111205", "cbor"})
    void aggregateLimitRejectsWholeBatchAndAcceptsTwentyFive(String protocol) throws Exception {
        String first = "batch-first-" + UUID.randomUUID();
        String second = "batch-second-" + UUID.randomUUID();
        createTable(first);
        try {
            createTable(second);
            try {
                for (String table : List.of(first, second)) {
                    ObjectNode seed = JSON.createObjectNode().put("TableName", table);
                    seed.set("Item", key("delete"));
                    call("20120810", "PutItem", seed, 200);
                }
                ObjectNode request = JSON.createObjectNode();
                ObjectNode tables = request.putObject("RequestItems");
                tables.set(first, writes(protocol, 13));
                tables.set(second, writes(protocol, 13));
                JsonNode error = call(protocol, "BatchWriteItem", request, 400);
                assertTrue(error.path("__type").asText().endsWith("ValidationException"));
                for (String table : List.of(first, second)) {
                    JsonNode scan = call("20120810", "Scan", JSON.createObjectNode().put("TableName", table), 200);
                    assertEquals(1, scan.path("Count").asInt());
                    assertEquals(key("delete"), scan.path("Items").get(0));
                }
                ((ArrayNode) tables.get(second)).remove(12);
                assertTrue(call(protocol, "BatchWriteItem", request, 200).path("UnprocessedItems").isEmpty());
                assertEquals(12, call("20120810", "Scan", JSON.createObjectNode().put("TableName", first), 200)
                        .path("Count").asInt());
                assertEquals(11, call("20120810", "Scan", JSON.createObjectNode().put("TableName", second), 200)
                        .path("Count").asInt());
            } finally {
                call("20120810", "DeleteTable", JSON.createObjectNode().put("TableName", second), 200);
            }
        } finally {
            call("20120810", "DeleteTable", JSON.createObjectNode().put("TableName", first), 200);
        }
    }

    private static void createTable(String name) throws Exception {
        call("20120810", "CreateTable", JSON.readTree("""
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(name)), 200);
    }

    private static ArrayNode writes(String protocol, int count) {
        ArrayNode result = JSON.createArrayNode();
        ObjectNode deleteKey = key("delete");
        if ("20111205".equals(protocol)) {
            deleteKey = JSON.createObjectNode();
            deleteKey.putObject("HashKeyElement").put("S", "delete");
        }
        result.addObject().putObject("DeleteRequest").set("Key", deleteKey);
        for (int index = 0; index < count - 1; index++) {
            result.addObject().putObject("PutRequest").set("Item", key("put-" + index));
        }
        return result;
    }

    private static ObjectNode key(String value) {
        ObjectNode key = JSON.createObjectNode();
        key.putObject("pk").put("S", value);
        return key;
    }

    private static JsonNode call(String protocol, String action, JsonNode request, int status) throws Exception {
        if ("cbor".equals(protocol)) {
            byte[] response = given().contentType("application/cbor").accept("application/cbor")
                    .body(CBOR.writeValueAsBytes(request))
                    .post("/service/DynamoDB_20120810/operation/" + action)
                    .then().statusCode(status).extract().asByteArray();
            return CBOR.readTree(response);
        }
        String response = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "DynamoDB_" + protocol + "." + action).body(request.toString())
                .post("/").then().statusCode(status).extract().asString();
        return JSON.readTree(response);
    }
}
