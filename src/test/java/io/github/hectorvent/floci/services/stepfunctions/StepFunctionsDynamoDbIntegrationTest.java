package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Step Functions DynamoDB integrations.
 * Tests both the optimized pattern (arn:aws:states:::dynamodb:*)
 * and the AWS SDK pattern (arn:aws:states:::aws-sdk:dynamodb:*).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsDynamoDbIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String DDB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String TABLE_NAME = "sfn-ddb-integration-test";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SFN_CONTENT_TYPE, ContentType.TEXT));
    }

    @Test
    @Order(0)
    void setup_createTestTable() {
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "KeySchema": [
                                {"AttributeName": "pk", "KeyType": "HASH"},
                                {"AttributeName": "sk", "KeyType": "RANGE"}
                            ],
                            "AttributeDefinitions": [
                                {"AttributeName": "pk", "AttributeType": "S"},
                                {"AttributeName": "sk", "AttributeType": "S"}
                            ],
                            "BillingMode": "PAY_PER_REQUEST"
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);
    }

    // ──────────────── AWS SDK Integration: Item CRUD ────────────────

    @Test
    @Order(1)
    void awsSdk_putItem() throws Exception {
        String output = executeSfn("aws-sdk-put", "aws-sdk:dynamodb:putItem", """
                {
                    "TableName": "%s",
                    "Item": {
                        "pk": {"S": "user-1"},
                        "sk": {"S": "profile"},
                        "name": {"S": "Alice"},
                        "age": {"N": "30"}
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertNotNull(result);
        // PutItem returns empty object (no Attributes unless ReturnValues specified)
        assertFalse(result.has("Attributes"));
    }

    @Test
    @Order(2)
    void awsSdk_getItem() throws Exception {
        String output = executeSfn("aws-sdk-get", "aws-sdk:dynamodb:getItem", """
                {
                    "TableName": "%s",
                    "Key": {
                        "pk": {"S": "user-1"},
                        "sk": {"S": "profile"}
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Item"), "Response must have Item field");
        JsonNode item = result.get("Item");
        assertEquals("Alice", item.path("name").path("S").asText());
        assertEquals("30", item.path("age").path("N").asText());
        assertEquals("user-1", item.path("pk").path("S").asText());
    }

    @Test
    @Order(3)
    void awsSdk_updateItem() throws Exception {
        String output = executeSfn("aws-sdk-update", "aws-sdk:dynamodb:updateItem", """
                {
                    "TableName": "%s",
                    "Key": {
                        "pk": {"S": "user-1"},
                        "sk": {"S": "profile"}
                    },
                    "UpdateExpression": "SET age = :newAge",
                    "ExpressionAttributeValues": {
                        ":newAge": {"N": "31"}
                    },
                    "ReturnValues": "ALL_NEW"
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Attributes"), "UpdateItem with ALL_NEW must return Attributes");
        assertEquals("31", result.path("Attributes").path("age").path("N").asText());
        assertEquals("Alice", result.path("Attributes").path("name").path("S").asText());
    }

    @Test
    @Order(4)
    void awsSdk_deleteItem() throws Exception {
        // Put a temp item first
        executeSfn("aws-sdk-del-setup", "aws-sdk:dynamodb:putItem", """
                {
                    "TableName": "%s",
                    "Item": {
                        "pk": {"S": "to-delete"},
                        "sk": {"S": "temp"}
                    }
                }
                """.formatted(TABLE_NAME));

        String output = executeSfn("aws-sdk-del", "aws-sdk:dynamodb:deleteItem", """
                {
                    "TableName": "%s",
                    "Key": {
                        "pk": {"S": "to-delete"},
                        "sk": {"S": "temp"}
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertNotNull(result);

        // Verify item is gone
        String getOutput = executeSfn("aws-sdk-del-verify", "aws-sdk:dynamodb:getItem", """
                {
                    "TableName": "%s",
                    "Key": {
                        "pk": {"S": "to-delete"},
                        "sk": {"S": "temp"}
                    }
                }
                """.formatted(TABLE_NAME));
        JsonNode getResult = mapper.readTree(getOutput);
        assertTrue(!getResult.has("Item") || getResult.get("Item").isNull(),
                "Deleted item should not be found");
    }

    // ──────────────── AWS SDK Integration: Query & Scan ────────────────

    @Test
    @Order(10)
    void awsSdk_query() throws Exception {
        // Ensure some data exists (from earlier tests)
        String output = executeSfn("aws-sdk-query", "aws-sdk:dynamodb:query", """
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "pk = :pk",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"}
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Items"), "Query response must have Items");
        assertTrue(result.has("Count"), "Query response must have Count");
        assertTrue(result.has("ScannedCount"), "Query response must have ScannedCount");
        assertTrue(result.get("Count").asInt() > 0, "Should find at least 1 item");
    }

    @Test
    @Order(11)
    void awsSdk_scan() throws Exception {
        String output = executeSfn("aws-sdk-scan", "aws-sdk:dynamodb:scan", """
                {
                    "TableName": "%s"
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Items"), "Scan response must have Items");
        assertTrue(result.has("Count"), "Scan response must have Count");
        assertTrue(result.has("ScannedCount"), "Scan response must have ScannedCount");
        assertTrue(result.get("Count").asInt() > 0, "Table should not be empty");
    }

    // ──────────────── AWS SDK Integration: Batch ────────────────

    @Test
    @Order(20)
    void awsSdk_batchWriteItem() throws Exception {
        String output = executeSfn("aws-sdk-batch-write", "aws-sdk:dynamodb:batchWriteItem", """
                {
                    "RequestItems": {
                        "%s": [
                            {
                                "PutRequest": {
                                    "Item": {
                                        "pk": {"S": "batch-1"},
                                        "sk": {"S": "a"},
                                        "val": {"S": "one"}
                                    }
                                }
                            },
                            {
                                "PutRequest": {
                                    "Item": {
                                        "pk": {"S": "batch-2"},
                                        "sk": {"S": "b"},
                                        "val": {"S": "two"}
                                    }
                                }
                            }
                        ]
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("UnprocessedItems"), "BatchWriteItem must return UnprocessedItems");
    }

    @Test
    @Order(21)
    void awsSdk_batchGetItem() throws Exception {
        String output = executeSfn("aws-sdk-batch-get", "aws-sdk:dynamodb:batchGetItem", """
                {
                    "RequestItems": {
                        "%s": {
                            "Keys": [
                                {"pk": {"S": "batch-1"}, "sk": {"S": "a"}},
                                {"pk": {"S": "batch-2"}, "sk": {"S": "b"}}
                            ]
                        }
                    }
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Responses"), "BatchGetItem must return Responses");
        assertTrue(result.path("Responses").has(TABLE_NAME));
        assertEquals(2, result.path("Responses").path(TABLE_NAME).size());
    }

    // ──────────────── AWS SDK Integration: Transactions ────────────────

    @Test
    @Order(30)
    void awsSdk_transactWriteItems() throws Exception {
        String output = executeSfn("aws-sdk-txn-write", "aws-sdk:dynamodb:transactWriteItems", """
                {
                    "TransactItems": [
                        {
                            "Put": {
                                "TableName": "%s",
                                "Item": {
                                    "pk": {"S": "txn-1"},
                                    "sk": {"S": "data"},
                                    "status": {"S": "created"}
                                }
                            }
                        }
                    ]
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertNotNull(result);
    }

    @Test
    @Order(31)
    void awsSdk_transactGetItems() throws Exception {
        String output = executeSfn("aws-sdk-txn-get", "aws-sdk:dynamodb:transactGetItems", """
                {
                    "TransactItems": [
                        {
                            "Get": {
                                "TableName": "%s",
                                "Key": {
                                    "pk": {"S": "txn-1"},
                                    "sk": {"S": "data"}
                                }
                            }
                        }
                    ]
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Responses"), "TransactGetItems must return Responses");
        assertEquals(1, result.get("Responses").size());
        assertEquals("created", result.path("Responses").path(0).path("Item")
                .path("status").path("S").asText());
    }

    // ──────────────── AWS SDK Integration: Table Management ────────────────

    @Test
    @Order(40)
    void awsSdk_createAndDescribeTable() throws Exception {
        String tempTable = "sfn-temp-table-" + System.currentTimeMillis();

        String createOutput = executeSfn("aws-sdk-create-tbl", "aws-sdk:dynamodb:createTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "id", "KeyType": "HASH"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "id", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(tempTable));

        JsonNode createResult = mapper.readTree(createOutput);
        assertTrue(createResult.has("TableDescription"), "CreateTable must return TableDescription");
        assertEquals(tempTable, createResult.path("TableDescription").path("TableName").asText());

        // DescribeTable
        String descOutput = executeSfn("aws-sdk-desc-tbl", "aws-sdk:dynamodb:describeTable", """
                {"TableName": "%s"}
                """.formatted(tempTable));
        JsonNode descResult = mapper.readTree(descOutput);
        assertTrue(descResult.has("Table"), "DescribeTable must return Table");
        assertEquals(tempTable, descResult.path("Table").path("TableName").asText());

        // ListTables
        String listOutput = executeSfn("aws-sdk-list-tbl", "aws-sdk:dynamodb:listTables", "{}");
        JsonNode listResult = mapper.readTree(listOutput);
        assertTrue(listResult.has("TableNames"), "ListTables must return TableNames");
        boolean found = false;
        for (JsonNode name : listResult.get("TableNames")) {
            if (tempTable.equals(name.asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Temp table should appear in ListTables");

        // DeleteTable
        String delOutput = executeSfn("aws-sdk-del-tbl", "aws-sdk:dynamodb:deleteTable", """
                {"TableName": "%s"}
                """.formatted(tempTable));
        JsonNode delResult = mapper.readTree(delOutput);
        assertTrue(delResult.has("TableDescription"), "DeleteTable must return TableDescription");
    }

    @Test
    @Order(41)
    void awsSdk_updateTable() throws Exception {
        // UpdateTable to add a GSI (or just call it -- even a no-op is fine for testing the dispatch)
        String output = executeSfn("aws-sdk-update-tbl", "aws-sdk:dynamodb:updateTable", """
                {
                    "TableName": "%s",
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("TableDescription"), "UpdateTable must return TableDescription");
    }

    // ──────────────── AWS SDK Integration: TTL ────────────────

    @Test
    @Order(50)
    void awsSdk_describeAndUpdateTimeToLive() throws Exception {
        // UpdateTimeToLive
        String updateOutput = executeSfn("aws-sdk-update-ttl", "aws-sdk:dynamodb:updateTimeToLive", """
                {
                    "TableName": "%s",
                    "TimeToLiveSpecification": {
                        "Enabled": true,
                        "AttributeName": "ttl"
                    }
                }
                """.formatted(TABLE_NAME));
        JsonNode updateResult = mapper.readTree(updateOutput);
        assertTrue(updateResult.has("TimeToLiveSpecification"),
                "UpdateTimeToLive must return TimeToLiveSpecification");

        // DescribeTimeToLive
        String descOutput = executeSfn("aws-sdk-desc-ttl", "aws-sdk:dynamodb:describeTimeToLive", """
                {"TableName": "%s"}
                """.formatted(TABLE_NAME));
        JsonNode descResult = mapper.readTree(descOutput);
        assertTrue(descResult.has("TimeToLiveDescription"),
                "DescribeTimeToLive must return TimeToLiveDescription");
    }

    // ──────────────── AWS SDK Integration: Tags ────────────────

    @Test
    @Order(60)
    void awsSdk_tagAndListTags() throws Exception {
        // Need the table ARN first
        String descOutput = executeSfn("aws-sdk-tag-desc", "aws-sdk:dynamodb:describeTable", """
                {"TableName": "%s"}
                """.formatted(TABLE_NAME));
        JsonNode descResult = mapper.readTree(descOutput);
        String tableArn = descResult.path("Table").path("TableArn").asText();

        // TagResource
        String tagOutput = executeSfn("aws-sdk-tag", "aws-sdk:dynamodb:tagResource", """
                {
                    "ResourceArn": "%s",
                    "Tags": [
                        {"Key": "env", "Value": "test"},
                        {"Key": "team", "Value": "sfn"}
                    ]
                }
                """.formatted(tableArn));
        assertNotNull(mapper.readTree(tagOutput));

        // ListTagsOfResource
        String listOutput = executeSfn("aws-sdk-list-tags", "aws-sdk:dynamodb:listTagsOfResource", """
                {"ResourceArn": "%s"}
                """.formatted(tableArn));
        JsonNode listResult = mapper.readTree(listOutput);
        assertTrue(listResult.has("Tags"), "ListTagsOfResource must return Tags");
        assertTrue(listResult.get("Tags").size() >= 2);

        // UntagResource
        String untagOutput = executeSfn("aws-sdk-untag", "aws-sdk:dynamodb:untagResource", """
                {
                    "ResourceArn": "%s",
                    "TagKeys": ["env"]
                }
                """.formatted(tableArn));
        assertNotNull(mapper.readTree(untagOutput));
    }

    // ──────────────── Optimized Integration: updateItem ────────────────

    @Test
    @Order(70)
    void optimized_updateItem() throws Exception {
        // Ensure item exists
        executeSfn("opt-update-setup", "aws-sdk:dynamodb:putItem", """
                {
                    "TableName": "%s",
                    "Item": {
                        "pk": {"S": "opt-update"},
                        "sk": {"S": "data"},
                        "score": {"N": "100"}
                    }
                }
                """.formatted(TABLE_NAME));

        String output = executeSfnOptimized("opt-updateitem", "dynamodb:updateItem", """
                {
                    "TableName": "%s",
                    "Key": {
                        "pk": {"S": "opt-update"},
                        "sk": {"S": "data"}
                    },
                    "UpdateExpression": "SET score = :s",
                    "ExpressionAttributeValues": {
                        ":s": {"N": "200"}
                    },
                    "ReturnValues": "ALL_NEW"
                }
                """.formatted(TABLE_NAME));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("Attributes"), "Optimized updateItem with ALL_NEW must return Attributes");
        assertEquals("200", result.path("Attributes").path("score").path("N").asText());
    }

    // ──────────────── Error Handling ────────────────

    @Test
    @Order(80)
    void awsSdk_unsupportedOperation_fails() throws Exception {
        // Use an action not supported by Floci's DynamoDB handler
        String definition = buildStateMachineDefinition("arn:aws:states:::aws-sdk:dynamodb:executeStatement", """
                {"Statement": "SELECT * FROM nonexistent"}
                """);

        String smArn = createStateMachine("aws-sdk-unsupported", definition);
        String execArn = startExecution(smArn, "{}");
        assertExecutionFailed(execArn);
    }

    @Test
    @Order(81)
    void unsupportedResource_fails() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::aws-sdk:unsupported:someAction", """
                {}
                """);

        String smArn = createStateMachine("unsupported-resource", definition);
        String execArn = startExecution(smArn, "{}");
        assertExecutionFailed(execArn);
    }

    // ──────────────── Helpers ────────────────

    private String executeSfn(String nameSuffix, String awsSdkResource, String parameters) throws Exception {
        String definition = buildStateMachineDefinition(
                "arn:aws:states:::" + awsSdkResource, parameters);
        String smArn = createStateMachine(nameSuffix + "-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");
        return waitForExecution(execArn);
    }

    private String executeSfnOptimized(String nameSuffix, String optimizedResource, String parameters) throws Exception {
        String definition = buildStateMachineDefinition(
                "arn:aws:states:::" + optimizedResource, parameters);
        String smArn = createStateMachine(nameSuffix + "-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");
        return waitForExecution(execArn);
    }

    private String buildStateMachineDefinition(String resource, String parameters) {
        return """
                {
                    "StartAt": "Action",
                    "States": {
                        "Action": {
                            "Type": "Task",
                            "Resource": "%s",
                            "Parameters": %s,
                            "End": true
                        }
                    }
                }
                """.formatted(resource, parameters.strip());
    }

    private String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            "definition": %s,
                            "roleArn": "%s"
                        }
                        """.formatted(name, quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "stateMachineArn": "%s",
                            "input": %s
                        }
                        """.formatted(smArn, quote(input)))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private String waitForExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when()
                    .post("/");
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private void assertExecutionFailed(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when()
                    .post("/");
            String status = resp.jsonPath().getString("status");
            if ("FAILED".equals(status)) {
                return; // Expected
            }
            if ("SUCCEEDED".equals(status)) {
                fail("Execution should have failed but succeeded: " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
