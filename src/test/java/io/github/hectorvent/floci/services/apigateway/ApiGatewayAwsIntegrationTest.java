package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for API Gateway AWS (non-proxy) integration type.
 * Tests the full flow: API Gateway → Step Functions → DynamoDB,
 * and API Gateway → DynamoDB directly.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayAwsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String DDB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String apiId;
    private static String rootId;
    private static String startResourceId;
    private static String ddbResourceId;
    private static String deploymentId;
    private static String stateMachineArn;

    private static final String TABLE_NAME = "apigw-sfn-test";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SFN_CONTENT_TYPE, ContentType.TEXT));
    }

    // ──────────────── Setup: DynamoDB table + SFN state machine ────────────────

    @Test @Order(0)
    void setup_createDynamoDbTable() {
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                            "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                            "BillingMode": "PAY_PER_REQUEST"
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test @Order(1)
    void setup_createStateMachine() throws Exception {
        // Simple Pass state machine — just echoes input back as output.
        // This tests the APIGW → SFN integration without depending on SFN DDB support.
        String definition = mapper.writeValueAsString(mapper.readTree("""
                {
                    "StartAt": "Echo",
                    "States": {
                        "Echo": {
                            "Type": "Pass",
                            "End": true
                        }
                    }
                }
                """));

        String body = """
                {
                    "name": "apigw-test-sm",
                    "definition": %s,
                    "roleArn": "arn:aws:iam::000000000000:role/test-role"
                }
                """.formatted(mapper.writeValueAsString(definition));

        String response = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        stateMachineArn = mapper.readTree(response).path("stateMachineArn").asText();
        assertNotNull(stateMachineArn);
        assertFalse(stateMachineArn.isEmpty());
    }

    // ──────────────── Setup: API Gateway REST API ────────────────

    @Test @Order(2)
    void setup_createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"aws-integration-test","description":"Test AWS integration type"}
                        """)
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test @Order(3)
    void setup_getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        assertNotNull(rootId);
    }

    @Test @Order(4)
    void setup_createStartResource() {
        startResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"start\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test @Order(5)
    void setup_configureStartMethod() throws Exception {
        // PUT method
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST")
                .then().statusCode(201);

        // PUT integration — AWS type targeting SFN StartExecution
        // Build the integration body programmatically to avoid escaping hell.
        // The VTL template wraps the input with DynamoDB typed attributes.
        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:states:action/StartExecution");
        var reqTemplates = mapper.createObjectNode();
        // VTL template that builds the SFN StartExecution request.
        // Passes the incoming body as the SFN execution input.
        String vtl = "{\"stateMachineArn\": \"" + stateMachineArn + "\", "
                + "\"input\": \"$util.escapeJavaScript($input.json('$'))\"}";
        reqTemplates.put("application/json", vtl);
        integrationNode.set("requestTemplates", reqTemplates);
        String integrationBody = mapper.writeValueAsString(integrationNode);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // PUT integration response (200 default)
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + startResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(6)
    void setup_createDdbResource() {
        ddbResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"items\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test @Order(7)
    void setup_configureDdbMethod() {
        // PUT method
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + ddbResourceId + "/methods/POST")
                .then().statusCode(201);

        // PUT integration — AWS type targeting DynamoDB PutItem directly
        String integrationBody = """
                {
                    "type": "AWS",
                    "httpMethod": "POST",
                    "uri": "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem",
                    "requestTemplates": {
                        "application/json": "{\\"TableName\\": \\"%s\\", \\"Item\\": {\\"id\\": {\\"S\\": \\"$input.path('$.id')\\"},\\"message\\": {\\"S\\": \\"$input.path('$.message')\\"}}}"
                    }
                }
                """.formatted(TABLE_NAME);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + ddbResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // PUT integration response (200 default)
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + ddbResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(8)
    void setup_deployAndCreateStage() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    // ──────────────── Test: APIGW → SFN → DynamoDB ────────────────

    @Test @Order(10)
    void awsIntegration_sfnStartExecution() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .body("{\"id\": \"apigw-sfn-1\", \"message\": \"hello from api gateway\"}")
                .when().post("/execute-api/" + apiId + "/test/start")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertTrue(result.has("executionArn"), "Response should have executionArn");
        assertTrue(result.has("startDate"), "Response should have startDate");
        String executionArn = result.path("executionArn").asText();
        assertTrue(executionArn.contains("apigw-test-sm"), "Execution ARN should reference state machine");
    }

    @Test @Order(11)
    void awsIntegration_sfnExecution_canDescribe() throws Exception {
        // Wait briefly for async execution to complete
        Thread.sleep(500);

        // Verify the execution completed successfully via DescribeExecution
        // (We use the executionArn from the previous test's StartExecution response)
        String listResponse = given()
                .header("X-Amz-Target", "AWSStepFunctions.ListExecutions")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\": \"" + stateMachineArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode executions = mapper.readTree(listResponse).path("executions");
        assertTrue(executions.isArray() && executions.size() > 0, "Should have at least one execution");
        assertEquals("SUCCEEDED", executions.get(0).path("status").asText());
    }

    // ──────────────── Test: APIGW → DynamoDB directly ────────────────

    @Test @Order(12)
    void awsIntegration_dynamoDbPutItem() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .body("{\"id\": \"apigw-ddb-1\", \"message\": \"direct dynamodb write\"}")
                .when().post("/execute-api/" + apiId + "/test/items")
                .then()
                .statusCode(200)
                .extract().asString();

        // PutItem returns empty object
        JsonNode result = mapper.readTree(response);
        assertNotNull(result);
    }

    @Test @Order(13)
    void awsIntegration_dynamoDbPutItem_verifyInDb() throws Exception {
        String response = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "apigw-ddb-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertTrue(result.has("Item"));
        assertEquals("apigw-ddb-1", result.path("Item").path("id").path("S").asText());
        assertEquals("direct dynamodb write", result.path("Item").path("message").path("S").asText());
    }

    // ──────────────── Test: Passthrough (no request template) ────────────────

    @Test @Order(14)
    void awsIntegration_passthrough_noRequestTemplate() throws Exception {
        // Create a new resource with AWS integration but no request template
        String ptResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"passthrough\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + ptResourceId + "/methods/POST")
                .then().statusCode(201);

        // No requestTemplates — body passes through as-is
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "type": "AWS",
                            "httpMethod": "POST",
                            "uri": "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem"
                        }
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + ptResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + ptResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        // Redeploy
        String newDeploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v2\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        // Update stage to new deployment
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/deploymentId\",\"value\":\"" + newDeploymentId + "\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200);

        // Call with DynamoDB PutItem payload directly (passthrough)
        String response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "%s",
                            "Item": {
                                "id": {"S": "passthrough-1"},
                                "message": {"S": "passthrough write"}
                            }
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/execute-api/" + apiId + "/test/passthrough")
                .then()
                .statusCode(200)
                .extract().asString();

        // Verify in DynamoDB
        String getResponse = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "passthrough-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(getResponse);
        assertTrue(result.has("Item"));
        assertEquals("passthrough-1", result.path("Item").path("id").path("S").asText());
    }

    // ──────────────── Test: VTL $context and $util ────────────────

    @Test @Order(15)
    void awsIntegration_vtlContextVariables() throws Exception {
        // Create a resource that uses $context variables in the template
        String ctxResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"context-test\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + ctxResourceId + "/methods/POST")
                .then().statusCode(201);

        // Template that uses $context.stage and $context.httpMethod
        String integrationBody = """
                {
                    "type": "AWS",
                    "httpMethod": "POST",
                    "uri": "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem",
                    "requestTemplates": {
                        "application/json": "{\\"TableName\\": \\"%s\\", \\"Item\\": {\\"id\\": {\\"S\\": \\"ctx-$context.stage\\"},\\"method\\": {\\"S\\": \\"$context.httpMethod\\"}}}"
                    }
                }
                """.formatted(TABLE_NAME);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + ctxResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + ctxResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        // Redeploy
        String dep = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v3\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/deploymentId\",\"value\":\"" + dep + "\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/context-test")
                .then()
                .statusCode(200);

        // Verify the item was written with context values
        String getResponse = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "ctx-test"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(getResponse);
        assertTrue(result.has("Item"), "Item should exist with context-derived key");
        assertEquals("ctx-test", result.path("Item").path("id").path("S").asText());
        assertEquals("POST", result.path("Item").path("method").path("S").asText());
    }

    // ──────────────── Test: Error path with selectionPattern ────────────────

    @Test @Order(20)
    void awsIntegration_errorPath_tableNotFound() throws Exception {
        // Call DynamoDB PutItem against a non-existent table.
        // Configure integration with a 400 error response using selectionPattern.
        String errResourceId = createResourceWithAwsIntegration("error-test",
                "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem",
                null,  // no request template — passthrough
                Map.of(
                        "200", new IntegrationResponseConfig("", ""),
                        "400", new IntegrationResponseConfig(".*ResourceNotFoundException.*", "")
                ));

        redeploy();

        String response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "nonexistent-table",
                            "Item": {"id": {"S": "x"}}
                        }
                        """)
                .when().post("/execute-api/" + apiId + "/test/error-test")
                .then()
                .statusCode(400)
                .extract().asString();

        // Error response should contain the error info
        JsonNode result = mapper.readTree(response);
        assertTrue(response.contains("ResourceNotFoundException")
                        || response.contains("nonexistent-table"),
                "Error response should mention the error: " + response);
    }

    @Test @Order(21)
    void awsIntegration_errorPath_defaultResponse() throws Exception {
        // Call DynamoDB with bad request — no selectionPattern match, falls back to default (200)
        String defResourceId = createResourceWithAwsIntegration("error-default",
                "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem",
                null,
                Map.of("200", new IntegrationResponseConfig("", "")));

        redeploy();

        // Missing required fields → service error, but default 200 response catches it
        String response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "nonexistent-table-2",
                            "Item": {"id": {"S": "x"}}
                        }
                        """)
                .when().post("/execute-api/" + apiId + "/test/error-default")
                .then()
                .extract().asString();

        // Default response should still return something (error info in body)
        assertNotNull(response);
        assertFalse(response.isEmpty());
    }

    // ──────────────── Test: Response mapping template ────────────────

    @Test @Order(30)
    void awsIntegration_responseMappingTemplate() throws Exception {
        // Create a DynamoDB GetItem integration with a response template
        // that transforms the DynamoDB response into a simpler format.

        // First, put an item to read
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Item": {"id": {"S": "resp-map-1"}, "message": {"S": "mapped response"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);

        // Response template extracts from the DynamoDB JSON response
        String responseTemplate = "#set($root = $util.parseJson($input.body()))\n"
                + "{\"id\": \"$root.Item.id.S\", \"message\": \"$root.Item.message.S\"}";

        String resId = createResourceWithAwsIntegration("response-map",
                "arn:aws:apigateway:us-east-1:dynamodb:action/GetItem",
                null,  // passthrough request
                Map.of("200", new IntegrationResponseConfig("", responseTemplate)));

        redeploy();

        String response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "resp-map-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/execute-api/" + apiId + "/test/response-map")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertEquals("resp-map-1", result.path("id").asText());
        assertEquals("mapped response", result.path("message").asText());
    }

    @Test @Order(31)
    void awsIntegration_responseMappingTemplate_listTables() throws Exception {
        // Use ListTables (returns {"TableNames": [...]}) and transform with response template
        String responseTemplate = "#set($root = $util.parseJson($input.body()))\n"
                + "{\"count\": $root.TableNames.size(), \"tables\": $input.body()}";

        createResourceWithAwsIntegration("list-tables",
                "arn:aws:apigateway:us-east-1:dynamodb:action/ListTables",
                "{}",  // static request template
                Map.of("200", new IntegrationResponseConfig("", responseTemplate)));

        redeploy();

        String response = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/list-tables")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertTrue(result.has("count"), "Response should have count field: " + response);
        assertTrue(result.path("count").asInt() > 0, "Should have at least one table");
    }

    // ──────────────── Test: Response $input.json() in response template ────────────────

    @Test @Order(32)
    void awsIntegration_responseMappingTemplate_inputJson() throws Exception {
        // Verify $input.json('$.path') works in response mapping templates
        // (where $input refers to the service response, not the original request)
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Item": {"id": {"S": "resp-json-1"}, "message": {"S": "json path test"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);

        // Response template uses $input.json() to extract from DynamoDB response
        String responseTemplate = "{\"itemId\": $input.json('$.Item.id.S'), \"msg\": $input.json('$.Item.message.S')}";

        createResourceWithAwsIntegration("resp-json",
                "arn:aws:apigateway:us-east-1:dynamodb:action/GetItem",
                null,
                Map.of("200", new IntegrationResponseConfig("", responseTemplate)));

        redeploy();

        String response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "resp-json-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/execute-api/" + apiId + "/test/resp-json")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertEquals("resp-json-1", result.path("itemId").asText());
        assertEquals("json path test", result.path("msg").asText());
    }

    // ──────────────── Test: SFN startDate format ────────────────

    @Test @Order(33)
    void awsIntegration_sfnStartDate_isEpochFloat() throws Exception {
        // Verify startDate is returned as a float (epoch seconds with millis), not an integer
        String response = given()
                .contentType(ContentType.JSON)
                .body("{\"id\": \"date-test-1\", \"message\": \"date format test\"}")
                .when().post("/execute-api/" + apiId + "/test/start")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(response);
        assertTrue(result.has("startDate"), "Response should have startDate");

        // startDate should be a number (not a string)
        assertTrue(result.path("startDate").isNumber(), "startDate should be a number");

        // Verify it's a float with decimal places (e.g., 1774722483.047), not an integer
        double startDate = result.path("startDate").asDouble();
        assertTrue(startDate > 1000000000.0, "startDate should be a reasonable epoch timestamp");

        // The raw JSON should contain a decimal point
        assertTrue(response.contains("."), "startDate should be serialized as a float with decimal: " + response);
    }

    // ──────────────── Test: Response parameter mapping (headers) ────────────────

    @Test @Order(40)
    void awsIntegration_responseParameterMapping_staticValue() throws Exception {
        // responseParameters with static value: 'value'
        String resId = createResourceWithAwsIntegrationAndResponseParams("header-static",
                "arn:aws:apigateway:us-east-1:dynamodb:action/ListTables",
                "{}",
                Map.of("200", new IntegrationResponseConfig("", "")),
                Map.of("200", Map.of(
                        "method.response.header.Access-Control-Allow-Origin", "'*'",
                        "method.response.header.X-Custom", "'hello-world'"
                )));

        redeploy();

        var response = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/header-static")
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("*", response.header("Access-Control-Allow-Origin"));
        assertEquals("hello-world", response.header("X-Custom"));
    }

    @Test @Order(41)
    void awsIntegration_responseParameterMapping_bodyField() throws Exception {
        // Put an item to read back
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Item": {"id": {"S": "header-body-1"}, "message": {"S": "for header test"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);

        // Map a response body field to a header
        createResourceWithAwsIntegrationAndResponseParams("header-body",
                "arn:aws:apigateway:us-east-1:dynamodb:action/GetItem",
                null,
                Map.of("200", new IntegrationResponseConfig("", "")),
                Map.of("200", Map.of(
                        "method.response.header.X-Item-Id", "integration.response.body.Item"
                )));

        redeploy();

        var response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "header-body-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/execute-api/" + apiId + "/test/header-body")
                .then()
                .statusCode(200)
                .extract().response();

        // The header should contain something from the Item field
        assertNotNull(response.header("X-Item-Id"), "Should have X-Item-Id header");
    }

    // ──────────────── Test: Content-Type negotiation ────────────────

    @Test @Order(50)
    void awsIntegration_contentTypeNegotiation_matchesTemplate() throws Exception {
        // Create integration with templates for both application/json and application/xml
        String resId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"ct-negotiate\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST")
                .then().statusCode(201);

        // Two templates: application/json writes "json-item", application/xml writes "xml-item"
        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem");
        var rt = mapper.createObjectNode();
        rt.put("application/json",
                "{\"TableName\": \"" + TABLE_NAME + "\", \"Item\": {\"id\": {\"S\": \"ct-json\"}, \"source\": {\"S\": \"json-template\"}}}");
        rt.put("application/xml",
                "{\"TableName\": \"" + TABLE_NAME + "\", \"Item\": {\"id\": {\"S\": \"ct-xml\"}, \"source\": {\"S\": \"xml-template\"}}}");
        integrationNode.set("requestTemplates", rt);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        redeploy();

        // Send with application/json → should use json template
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/ct-negotiate")
                .then().statusCode(200);

        // Verify json template was used
        String getResp = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("{\"TableName\": \"" + TABLE_NAME + "\", \"Key\": {\"id\": {\"S\": \"ct-json\"}}}")
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(getResp);
        assertTrue(result.has("Item"), "JSON template should have been selected");
        assertEquals("json-template", result.path("Item").path("source").path("S").asText());
    }

    // ──────────────── Test: passthroughBehavior ────────────────

    @Test @Order(51)
    void awsIntegration_passthroughBehavior_never_rejects() throws Exception {
        // passthroughBehavior=NEVER with no templates → 415
        String resId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"pt-never\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:dynamodb:action/ListTables");
        integrationNode.put("passthroughBehavior", "NEVER");

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        redeploy();

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/pt-never")
                .then().statusCode(415);
    }

    @Test @Order(52)
    void awsIntegration_passthroughBehavior_whenNoTemplates_noMatchRejects() throws Exception {
        // passthroughBehavior=WHEN_NO_TEMPLATES with templates for text/plain → application/json should be rejected
        String resId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"pt-nomatch\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:dynamodb:action/ListTables");
        integrationNode.put("passthroughBehavior", "WHEN_NO_TEMPLATES");
        var rt = mapper.createObjectNode();
        rt.put("text/plain", "{}");
        integrationNode.set("requestTemplates", rt);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        redeploy();

        // Send application/json but only text/plain template exists → 415
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/pt-nomatch")
                .then().statusCode(415);
    }

    @Test @Order(53)
    void awsIntegration_passthroughBehavior_whenNoMatch_passesThrough() throws Exception {
        // passthroughBehavior=WHEN_NO_MATCH (default) with template for text/plain only
        // → application/json should passthrough
        String resId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"pt-default\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:dynamodb:action/ListTables");
        integrationNode.put("passthroughBehavior", "WHEN_NO_MATCH");
        var rt = mapper.createObjectNode();
        rt.put("text/plain", "{\"bad\":true}");
        integrationNode.set("requestTemplates", rt);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        redeploy();

        // Send application/json → no match, but WHEN_NO_MATCH means passthrough
        // Body is empty JSON {} which is valid for ListTables
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/pt-default")
                .then().statusCode(200);
    }

    // ──────────────── Test: Request parameter mapping ────────────────

    @Test @Order(60)
    void awsIntegration_requestParameterMapping() throws Exception {
        // Map a query string param to be used in the VTL template via $input.params()
        String resId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"req-param\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST")
                .then().statusCode(201);

        // Map method.request.querystring.itemId → integration.request.querystring.itemId
        // and use it in the template
        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:dynamodb:action/PutItem");
        var reqParams = mapper.createObjectNode();
        reqParams.put("integration.request.querystring.itemId", "method.request.querystring.itemId");
        integrationNode.set("requestParameters", reqParams);
        var rt = mapper.createObjectNode();
        rt.put("application/json",
                "{\"TableName\": \"" + TABLE_NAME + "\", \"Item\": {\"id\": {\"S\": \"$input.params('itemId')\"}, \"source\": {\"S\": \"req-param-mapped\"}}}");
        integrationNode.set("requestTemplates", rt);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        redeploy();

        // Call with query param
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .queryParam("itemId", "param-123")
                .when().post("/execute-api/" + apiId + "/test/req-param")
                .then().statusCode(200);

        // Verify the item was written with the query param value
        String getResp = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("{\"TableName\": \"" + TABLE_NAME + "\", \"Key\": {\"id\": {\"S\": \"param-123\"}}}")
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        JsonNode result = mapper.readTree(getResp);
        assertTrue(result.has("Item"), "Item should exist with param-mapped key");
        assertEquals("param-123", result.path("Item").path("id").path("S").asText());
        assertEquals("req-param-mapped", result.path("Item").path("source").path("S").asText());
    }

    // ──────────────── Test: Response body JSONPath (deep) ────────────────

    @Test @Order(61)
    void awsIntegration_responseBodyJsonPath_deep() throws Exception {
        // Put a nested item to read back
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Item": {"id": {"S": "deep-path-1"}, "message": {"S": "deep value"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);

        // Map a deep JSON path from response body to a header
        createResourceWithAwsIntegrationAndResponseParams("deep-path",
                "arn:aws:apigateway:us-east-1:dynamodb:action/GetItem",
                null,
                Map.of("200", new IntegrationResponseConfig("", "")),
                Map.of("200", Map.of(
                        "method.response.header.X-Item-Message", "integration.response.body.Item.message.S"
                )));

        redeploy();

        var response = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"id": {"S": "deep-path-1"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/execute-api/" + apiId + "/test/deep-path")
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("deep value", response.header("X-Item-Message"));
    }

    // ──────────────── Helpers ────────────────

    private record IntegrationResponseConfig(String selectionPattern, String responseTemplate) {}

    private String createResourceWithAwsIntegrationAndResponseParams(
            String pathPart, String uri, String requestTemplate,
            Map<String, IntegrationResponseConfig> responses,
            Map<String, Map<String, String>> responseParams) throws Exception {
        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", uri);
        if (requestTemplate != null) {
            var rt = mapper.createObjectNode();
            rt.put("application/json", requestTemplate);
            integrationNode.set("requestTemplates", rt);
        }

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        for (var entry : responses.entrySet()) {
            var irNode = mapper.createObjectNode();
            irNode.put("selectionPattern", entry.getValue().selectionPattern());
            var respTemplates = mapper.createObjectNode();
            respTemplates.put("application/json", entry.getValue().responseTemplate());
            irNode.set("responseTemplates", respTemplates);

            // Add response parameters if provided
            Map<String, String> params = responseParams != null ? responseParams.get(entry.getKey()) : null;
            if (params != null && !params.isEmpty()) {
                var paramsNode = mapper.createObjectNode();
                params.forEach(paramsNode::put);
                irNode.set("responseParameters", paramsNode);
            }

            given()
                    .contentType(ContentType.JSON)
                    .body(mapper.writeValueAsString(irNode))
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/" + entry.getKey())
                    .then().statusCode(201);
        }

        return resourceId;
    }

    private String createResourceWithAwsIntegration(String pathPart, String uri,
                                                     String requestTemplate,
                                                     Map<String, IntegrationResponseConfig> responses) throws Exception {
        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", uri);
        if (requestTemplate != null) {
            var rt = mapper.createObjectNode();
            rt.put("application/json", requestTemplate);
            integrationNode.set("requestTemplates", rt);
        }

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        for (var entry : responses.entrySet()) {
            var irNode = mapper.createObjectNode();
            irNode.put("selectionPattern", entry.getValue().selectionPattern());
            var respTemplates = mapper.createObjectNode();
            respTemplates.put("application/json", entry.getValue().responseTemplate());
            irNode.set("responseTemplates", respTemplates);

            given()
                    .contentType(ContentType.JSON)
                    .body(mapper.writeValueAsString(irNode))
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/" + entry.getKey())
                    .then().statusCode(201);
        }

        return resourceId;
    }

    private void redeploy() {
        String dep = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"redeploy\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/deploymentId\",\"value\":\"" + dep + "\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200);
    }

    // ──────────────── Cleanup ────────────────

    @Test @Order(99)
    void cleanup() {
        // Delete stage
        given().when().delete("/restapis/" + apiId + "/stages/test").then().statusCode(anyOf(200, 202, 204));

        // Delete REST API
        given().when().delete("/restapis/" + apiId).then().statusCode(anyOf(200, 202, 204));

        // Delete table
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(DDB_CONTENT_TYPE)
                .body("{\"TableName\": \"" + TABLE_NAME + "\"}")
                .when().post("/")
                .then().statusCode(200);

        // Delete state machine
        if (stateMachineArn != null) {
            given()
                    .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("{\"stateMachineArn\": \"" + stateMachineArn + "\"}")
                    .when().post("/")
                    .then().statusCode(200);
        }
    }

    private static org.hamcrest.Matcher<Integer> anyOf(int... values) {
        org.hamcrest.Matcher<Integer>[] matchers = new org.hamcrest.Matcher[values.length];
        for (int i = 0; i < values.length; i++) {
            matchers[i] = org.hamcrest.Matchers.equalTo(values[i]);
        }
        return org.hamcrest.Matchers.anyOf(matchers);
    }
}
