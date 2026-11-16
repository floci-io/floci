package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLSortKeyReadsIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TABLE = "PartiQLSortKeyReads";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int testPort;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() throws Exception {
        testPort = RestAssured.port;
        call("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [
                    {"AttributeName": "pk", "KeyType": "HASH"},
                    {"AttributeName": "sk", "KeyType": "RANGE"}
                ],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "sk", "AttributeType": "S"},
                    {"AttributeName": "l", "AttributeType": "S"}
                ],
                "LocalSecondaryIndexes": [{
                    "IndexName": "lsi",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "l", "KeyType": "RANGE"}
                    ],
                    "Projection": {"ProjectionType": "ALL"}
                }],
                "BillingMode": "PAY_PER_REQUEST"
            }
            """.formatted(TABLE));
        for (int i = 1; i <= 3; i++) {
            put("{\"pk\": {\"S\": \"p\"}, \"sk\": {\"S\": \"row-%1$d\"}, \"x\": {\"N\": \"%1$d\"}}".formatted(i));
        }
        put("{\"pk\": {\"S\": \"lk\"}, \"sk\": {\"S\": \"r1\"}, \"l\": {\"S\": \"a\"}}");
        put("{\"pk\": {\"S\": \"lk\"}, \"sk\": {\"S\": \"r2\"}, \"l\": {\"S\": \"b\"}}");
        put("{\"pk\": {\"S\": \"lk\"}, \"sk\": {\"S\": \"r3\"}, \"l\": {\"S\": \"b\"}}");
        put("{\"pk\": {\"S\": \"big\"}, \"sk\": {\"S\": \"big\"}, \"pad\": {\"S\": \"%s\"}}".formatted("x".repeat(5000)));
        put("{\"pk\": {\"S\": \"big\"}, \"sk\": {\"S\": \"row-1\"}}");
    }

    @ParameterizedTest(name = "{0} under Limit {1}")
    @Order(2)
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        pk='p' AND (sk='row-1' OR sk='row-2')                                 | 1 | [row-1] + 0.5;[row-2] 0.5
        pk='p' AND (sk='row-0' OR sk='row-2')                                 | 1 | [row-2] 1.0
        pk='p' AND (sk='row-1' OR sk='row-9')                                 | 1 | [row-1] + 0.5;[] 0.5
        pk='p' AND (sk='row-1' OR sk='row-2' OR sk='row-3')                   | 2 | [row-1,row-2] + 1.0;[row-3] 0.5
        pk='p' AND sk IN ['row-1','row-3'] ORDER BY sk DESC                   | 1 | [row-3] + 0.5;[row-1] 0.5
        pk='p' AND (sk='row-1' OR sk='row-3') ORDER BY pk DESC                | 1 | [row-1] + 0.5;[row-3] 0.5
        pk='p' AND (sk BETWEEN 'row-0' AND 'row-1' OR sk BETWEEN 'x' AND 'y') | 1 | [row-1] + 0.5;[] 0.5
        pk='p' AND (sk='row-1' OR sk BETWEEN 'row-3' AND 'row-5')             | 2 | [row-1,row-3] + 1.0;[] 0.5
        pk='p' AND (sk='row-1' OR sk='row-2' OR sk='row-3') AND x = 2         | 1 | [] + 0.5;[row-2] + 0.5;[] 0.5
        """)
    void readsEachSortKeyBranchInKeyOrder(String where, int limit, String pages) throws Exception {
        assertEquals(List.of(pages.split(";")), walk("SELECT * FROM \"" + TABLE + "\" WHERE " + where, limit));
    }

    @Test
    @Order(3)
    void chargesEachBranchOnItsOwn() throws Exception {
        String keys = "SELECT * FROM \"" + TABLE + "\" WHERE pk='p' AND (sk='row-1' OR sk='row-3')";
        assertEquals(2.0, read(keys, true).path("ConsumedCapacity").path("CapacityUnits").asDouble());
        assertEquals(1.0, read(keys, false).path("ConsumedCapacity").path("CapacityUnits").asDouble());
        String big = "SELECT * FROM \"" + TABLE + "\" WHERE pk='big' AND (sk='big' OR sk='row-1')";
        assertEquals(3.0, read(big, true).path("ConsumedCapacity").path("CapacityUnits").asDouble());
        assertEquals(1.5, read(big, false).path("ConsumedCapacity").path("CapacityUnits").asDouble());
    }

    @Test
    @Order(4)
    void readsEachLsiKeyOnItsOwn() throws Exception {
        String keys = "SELECT * FROM \"" + TABLE + "\".\"lsi\" WHERE pk='lk' AND ";
        assertEquals(List.of("[r1] + 0.5", "[] 0.5"), walk(keys + "(l='a' OR l='z')", 1));
        JsonNode capacity = read(keys + "(l='a' OR l='b')", true).path("ConsumedCapacity");
        assertEquals(2.0, capacity.path("CapacityUnits").asDouble());
        assertEquals(2.0, capacity.path("LocalSecondaryIndexes").path("lsi").path("CapacityUnits").asDouble());
    }

    private List<String> walk(String statement, int limit) throws Exception {
        List<String> pages = new ArrayList<>();
        String token = null;
        do {
            ObjectNode request = MAPPER.createObjectNode()
                    .put("Statement", statement)
                    .put("Limit", limit)
                    .put("ReturnConsumedCapacity", "TOTAL");
            if (token != null) {
                request.put("NextToken", token);
            }
            JsonNode page = call("ExecuteStatement", request.toString());
            List<String> keys = new ArrayList<>();
            page.path("Items").forEach(item -> keys.add(item.path("sk").path("S").asText()));
            token = page.hasNonNull("NextToken") ? page.get("NextToken").asText() : null;
            pages.add("[" + String.join(",", keys) + "]" + (token == null ? "" : " +") + " "
                    + page.path("ConsumedCapacity").path("CapacityUnits").asDouble());
        } while (token != null && pages.size() < 8);
        return pages;
    }

    private JsonNode read(String statement, boolean consistentRead) throws Exception {
        return call("ExecuteStatement", MAPPER.createObjectNode()
                .put("Statement", statement)
                .put("ConsistentRead", consistentRead)
                .put("ReturnConsumedCapacity", "INDEXES")
                .toString());
    }

    private void put(String item) throws Exception {
        call("PutItem", "{\"TableName\": \"" + TABLE + "\", \"Item\": " + item + "}");
    }

    private JsonNode call(String action, String body) throws Exception {
        String response = given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();
        return MAPPER.readTree(response);
    }

    @AfterAll
    static void cleanup() {
        given()
                .port(testPort)
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(CT)
                .body("{\"TableName\": \"" + TABLE + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
