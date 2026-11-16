package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbConditionalOperatorIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @ParameterizedTest
    @CsvSource({"Query,OR,3", "Scan,OR,3", "Query,AND,1", "Scan,AND,1", "Query,DEFAULT,1", "Scan,DEFAULT,1"})
    void legacyFiltersCombineConditionsWithoutChangingReadPages(String action, String operator, int expected) throws Exception {
        try (DynamoDbClient client = AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1)) {
            String table = "conditional-" + UUID.randomUUID();
            client.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk")
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()));
            try {
                for (int index = 0; index < 4; index++) {
                    int value = index;
                    client.putItem(r -> r.tableName(table).item(Map.of("pk", text("group"),
                            "sk", text(Integer.toString(value)), "left", text(value < 2 ? "yes" : "no"),
                            "right", text(value % 2 == 0 ? "yes" : "no"))));
                }
                Map<String, Condition> filter = Map.of("left", equalToYes(), "right", equalToYes());
                String conditionalOperator = "DEFAULT".equals(operator) ? null : operator;
                Map<String, AttributeValue> cursor = null;
                List<Map<String, AttributeValue>> items = new ArrayList<>();
                int scanned = 0;
                int pages = 0;
                do {
                    if ("Query".equals(action)) {
                        QueryResponse response = client.query(QueryRequest.builder().tableName(table)
                                .keyConditions(Map.of("pk", Condition.builder().comparisonOperator("EQ")
                                        .attributeValueList(text("group")).build()))
                                .queryFilter(filter).conditionalOperator(conditionalOperator).limit(1)
                                .exclusiveStartKey(cursor).build());
                        items.addAll(response.items());
                        scanned += response.scannedCount();
                        cursor = response.lastEvaluatedKey();
                    } else {
                        ScanResponse response = client.scan(ScanRequest.builder().tableName(table)
                                .scanFilter(filter).conditionalOperator(conditionalOperator).limit(1)
                                .exclusiveStartKey(cursor).build());
                        items.addAll(response.items());
                        scanned += response.scannedCount();
                        cursor = response.lastEvaluatedKey();
                    }
                    pages++;
                    if (pages > 5) {
                        throw new AssertionError("Pagination did not terminate");
                    }
                } while (cursor != null && !cursor.isEmpty());
                assertEquals(4, scanned);
                assertEquals(expected, items.size());
                assertEquals("OR".equals(operator) ? List.of("0", "1", "2") : List.of("0"),
                        items.stream().map(i -> i.get("sk").s()).sorted().toList());
                assertCborCount(table, action, operator, expected);
            } finally {
                client.deleteTable(r -> r.tableName(table));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"Query,OR,!!!!,400", "Scan,OR,!!!!,400", "Query,AND,!!!!,400", "Scan,AND,!!!!,400",
            "Query,OR,Yg==,200", "Scan,OR,Yg==,200", "Query,AND,Yg==,200", "Scan,AND,Yg==,200"})
    void binaryFilterOperandsAreValidatedBeforeCombination(String action, String operator,
                                                           String encoded, int status) throws Exception {
        try (DynamoDbClient client = AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1)) {
            String table = "binary-filter-" + UUID.randomUUID();
            client.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk")
                            .attributeType(ScalarAttributeType.S).build()));
            try {
                client.putItem(r -> r.tableName(table).item(Map.of("pk", text("group"), "left", text("yes"),
                        "right", AttributeValue.builder().b(SdkBytes.fromUtf8String("a")).build())));
                ObjectNode request = JSON.createObjectNode().put("TableName", table).put("ConditionalOperator", operator);
                request.set("Query".equals(action) ? "QueryFilter" : "ScanFilter", JSON.readTree("""
                        {"left":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"yes"}]},
                         "right":{"ComparisonOperator":"LT","AttributeValueList":[{"B":"%s"}]}}
                        """.formatted(encoded)));
                if ("Query".equals(action)) {
                    request.set("KeyConditions", JSON.readTree("""
                            {"pk":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"group"}]}}
                            """));
                }
                String response = given().contentType("application/x-amz-json-1.0")
                        .header("X-Amz-Target", "DynamoDB_20120810." + action)
                        .body(JSON.writeValueAsBytes(request)).post("/").then().statusCode(status).extract().asString();
                if (status == 400) {
                    assertEquals("SerializationException", JSON.readTree(response).path("__type").asText()
                            .replace("com.amazonaws.dynamodb.v20120810#", ""));
                } else {
                    assertEquals(1, JSON.readTree(response).path("Count").asInt());
                }
            } finally {
                client.deleteTable(r -> r.tableName(table));
            }
        }
    }

    private static void assertCborCount(String table, String action, String operator, int expected) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("TableName", table).put("Select", "COUNT");
        if (!"DEFAULT".equals(operator)) {
            request.put("ConditionalOperator", operator);
        }
        request.set("Query".equals(action) ? "QueryFilter" : "ScanFilter", JSON.readTree("""
                {"left":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"yes"}]},
                 "right":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"yes"}]}}
                """));
        if ("Query".equals(action)) {
            request.set("KeyConditions", JSON.readTree("""
                    {"pk":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"group"}]}}
                    """));
        }
        byte[] response = given().contentType("application/cbor").accept("application/cbor")
                .body(CBOR.writeValueAsBytes(request)).post("/service/DynamoDB_20120810/operation/" + action)
                .then().statusCode(200).extract().asByteArray();
        JsonNode result = CBOR.readTree(response);
        assertEquals(expected, result.path("Count").asInt());
        assertEquals(4, result.path("ScannedCount").asInt());
    }

    private static Condition equalToYes() {
        return Condition.builder().comparisonOperator("EQ").attributeValueList(text("yes")).build();
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
