package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end compatibility tests: API Gateway → Step Functions (JSONata) → DynamoDB.
 *
 * <p>Exercises all five CRUDL operations via HTTP through a deployed API Gateway stage,
 * backed by Express Step Functions state machines using JSONata and optimised DynamoDB
 * integrations.
 *
 * <p>Run against real AWS:
 * <pre>
 *   FLOCI_TARGET=aws \
 *   SFN_ROLE_ARN=arn:aws:iam::123456789012:role/sfn-role \
 *   APIGW_ROLE_ARN=arn:aws:iam::123456789012:role/apigw-sfn-role \
 *     mvn compile exec:java -Dexec.args="apigw-sfn-jsonata-crudl"
 * </pre>
 */
@DisplayName("API Gateway → SFN JSONata → DynamoDB CRUDL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApigwSfnJsonataCrudlTests {

    private static final String TABLE_BASE = "apigw-sfn-crudl";
    private static final String STAGE = "v1";

    private static DynamoDbClient ddb;
    private static SfnClient sfn;
    private static ApiGatewayClient apigw;
    private static HttpClient http;

    private static String tableName;
    private static String createArn;
    private static String readArn;
    private static String updateArn;
    private static String deleteArn;
    private static String listArn;
    private static String apiId;
    private static String baseUrl;

    private static boolean isRealAws;
    private static String sfnRoleArn;
    private static String apigwRoleArn;
    private static Region region;

    @BeforeAll
    static void setup() throws Exception {
        isRealAws = TestFixtures.isRealAws();
        region = Region.US_EAST_1;

        sfnRoleArn = isRealAws
                ? System.getenv("SFN_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/sfn-role";
        apigwRoleArn = isRealAws
                ? System.getenv("APIGW_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/apigw-role";

        if (isRealAws && (sfnRoleArn == null || apigwRoleArn == null)) {
            Assumptions.abort("SFN_ROLE_ARN or APIGW_ROLE_ARN not set");
        }

        tableName = TABLE_BASE + "-" + System.currentTimeMillis();

        if (isRealAws) {
            ddb = DynamoDbClient.builder().region(region).build();
            sfn = SfnClient.builder().region(region).build();
            apigw = ApiGatewayClient.builder().region(region).build();
        } else {
            URI endpoint = TestFixtures.endpoint();
            ddb = DynamoDbClient.builder()
                    .endpointOverride(endpoint)
                    .region(region)
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                    .build();
            sfn = SfnClient.builder()
                    .endpointOverride(endpoint)
                    .region(region)
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true)
                            .build())
                    .build();
            apigw = ApiGatewayClient.builder()
                    .endpointOverride(endpoint)
                    .region(region)
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                    .build();
        }

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        // Create DynamoDB table
        createTable(ddb, tableName);
        if (isRealAws) Thread.sleep(2000);

        // Create state machines
        createArn = createSm(sfn, sfnRoleArn, tableName, "create", smCreate(tableName));
        readArn = createSm(sfn, sfnRoleArn, tableName, "read", smRead(tableName));
        updateArn = createSm(sfn, sfnRoleArn, tableName, "update", smUpdate(tableName));
        deleteArn = createSm(sfn, sfnRoleArn, tableName, "delete", smDelete(tableName));
        listArn = createSm(sfn, sfnRoleArn, tableName, "list", smList(tableName));

        if (createArn == null || readArn == null || updateArn == null || deleteArn == null || listArn == null) {
            Assumptions.abort("Failed to create state machines");
        }

        if (isRealAws) Thread.sleep(2000);

        // Build API Gateway
        apiId = buildApi(apigw, region.id(), apigwRoleArn, createArn, readArn, updateArn, deleteArn, listArn);
        String deployId = apigw.createDeployment(b -> b.restApiId(apiId)).id();
        apigw.createStage(b -> b.restApiId(apiId).stageName(STAGE).deploymentId(deployId));

        if (isRealAws) Thread.sleep(3000);

        baseUrl = isRealAws
                ? "https://" + apiId + ".execute-api." + region.id() + ".amazonaws.com/" + STAGE
                : TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE;
    }

    @AfterAll
    static void cleanup() {
        if (createArn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(createArn)); } catch (Exception ignored) {}
        }
        if (readArn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(readArn)); } catch (Exception ignored) {}
        }
        if (updateArn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(updateArn)); } catch (Exception ignored) {}
        }
        if (deleteArn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(deleteArn)); } catch (Exception ignored) {}
        }
        if (listArn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(listArn)); } catch (Exception ignored) {}
        }
        if (apiId != null && apigw != null) {
            try { apigw.deleteRestApi(b -> b.restApiId(apiId)); } catch (Exception ignored) {}
        }
        if (tableName != null && ddb != null) {
            try { ddb.deleteTable(b -> b.tableName(tableName)); } catch (Exception ignored) {}
        }
        if (ddb != null) ddb.close();
        if (sfn != null) sfn.close();
        if (apigw != null) apigw.close();
    }

    @Test
    @Order(1)
    @DisplayName("Create - POST /items")
    void create() throws Exception {
        HttpResponse<String> resp = post(http, baseUrl + "/items",
                "{\"id\":\"item-1\",\"name\":\"Widget\",\"value\":\"blue\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("item-1");
    }

    @Test
    @Order(2)
    @DisplayName("Read - GET /items/item-1")
    void read() throws Exception {
        HttpResponse<String> resp = get(http, baseUrl + "/items/item-1");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("Widget");
        assertThat(resp.body()).contains("blue");
    }

    @Test
    @Order(3)
    @DisplayName("Update - PUT /items/item-1")
    void update() throws Exception {
        HttpResponse<String> updateResp = put(http, baseUrl + "/items/item-1",
                "{\"name\":\"Widget Pro\",\"value\":\"green\"}");
        HttpResponse<String> verifyResp = get(http, baseUrl + "/items/item-1");

        assertThat(updateResp.statusCode()).isEqualTo(200);
        assertThat(verifyResp.body()).contains("Widget Pro");
        assertThat(verifyResp.body()).contains("green");
    }

    @Test
    @Order(4)
    @DisplayName("List - GET /items")
    void list() throws Exception {
        HttpResponse<String> resp = get(http, baseUrl + "/items");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("item-1");
    }

    @Test
    @Order(5)
    @DisplayName("Delete - DELETE /items/item-1")
    void delete() throws Exception {
        HttpResponse<String> resp = delete(http, baseUrl + "/items/item-1");

        assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(6)
    @DisplayName("Read after delete - GET /items/item-1")
    void readAfterDelete() throws Exception {
        HttpResponse<String> resp = get(http, baseUrl + "/items/item-1");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("false");
        assertThat(resp.body()).doesNotContain("Widget");
    }

    // State machine definitions

    private static String smCreate(String table) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "PutItem",
                  "States": {
                    "PutItem": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:putItem",
                      "Arguments": {
                        "TableName": "TABLE",
                        "Item": {
                          "pk": {"S": "{% $states.input.id %}"},
                          "sk": {"S": "item"},
                          "name": {"S": "{% $states.input.name %}"},
                          "value": {"S": "{% $states.input.value %}"}
                        }
                      },
                      "Output": {"id": "{% $states.input.id %}", "created": true},
                      "End": true
                    }
                  }
                }""".replace("TABLE", table);
    }

    private static String smRead(String table) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "GetItem",
                  "States": {
                    "GetItem": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:getItem",
                      "Arguments": {
                        "TableName": "TABLE",
                        "Key": {
                          "pk": {"S": "{% $states.input.id %}"},
                          "sk": {"S": "item"}
                        }
                      },
                      "Output": "{% $exists($states.result.Item) ? {\\n  \\"found\\": true,\\n  \\"id\\": $states.result.Item.pk.S,\\n  \\"name\\": $states.result.Item.name.S,\\n  \\"value\\": $states.result.Item.value.S\\n} : {\\"found\\": false} %}",
                      "End": true
                    }
                  }
                }""".replace("TABLE", table);
    }

    private static String smUpdate(String table) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "UpdateItem",
                  "States": {
                    "UpdateItem": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:updateItem",
                      "Arguments": {
                        "TableName": "TABLE",
                        "Key": {
                          "pk": {"S": "{% $states.input.id %}"},
                          "sk": {"S": "item"}
                        },
                        "UpdateExpression": "SET #n = :name, #v = :value",
                        "ExpressionAttributeNames": {"#n": "name", "#v": "value"},
                        "ExpressionAttributeValues": {
                          ":name":  {"S": "{% $states.input.name %}"},
                          ":value": {"S": "{% $states.input.value %}"}
                        }
                      },
                      "Output": {"id": "{% $states.input.id %}", "updated": true},
                      "End": true
                    }
                  }
                }""".replace("TABLE", table);
    }

    private static String smDelete(String table) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "DeleteItem",
                  "States": {
                    "DeleteItem": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:deleteItem",
                      "Arguments": {
                        "TableName": "TABLE",
                        "Key": {
                          "pk": {"S": "{% $states.input.id %}"},
                          "sk": {"S": "item"}
                        }
                      },
                      "Output": {"id": "{% $states.input.id %}", "deleted": true},
                      "End": true
                    }
                  }
                }""".replace("TABLE", table);
    }

    private static String smList(String table) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Scan",
                  "States": {
                    "Scan": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:dynamodb:scan",
                      "Arguments": {
                        "TableName": "TABLE"
                      },
                      "Output": {
                        "count": "{% $states.result.Count %}",
                        "items": "{% [$states.result.Items.{\\"id\\": pk.S, \\"name\\": name.S, \\"value\\": value.S}] %}"
                      },
                      "End": true
                    }
                  }
                }""".replace("TABLE", table);
    }

    // Setup helpers

    private static void createTable(DynamoDbClient ddb, String tableName) {
        try {
            ddb.createTable(b -> b
                    .tableName(tableName)
                    .keySchema(
                            KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("pk")
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk")
                                    .attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST));
        } catch (ResourceInUseException ignored) {}
    }

    private static String createSm(SfnClient sfn, String roleArn, String tableName, String op, String definition) {
        try {
            String name = TABLE_BASE + "-" + op + "-" + tableName.substring(tableName.lastIndexOf('-') + 1);
            return sfn.createStateMachine(b -> b
                    .name(name)
                    .definition(definition)
                    .type(StateMachineType.EXPRESS)
                    .roleArn(roleArn)).stateMachineArn();
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildApi(ApiGatewayClient apigw, String region, String roleArn,
                                    String createArn, String readArn, String updateArn,
                                    String deleteArn, String listArn) {
        String apiId = apigw.createRestApi(b -> b.name(TABLE_BASE + "-" + System.currentTimeMillis())).id();
        String rootId = apigw.getResources(b -> b.restApiId(apiId)).items()
                .stream().filter(r -> "/".equals(r.path())).findFirst()
                .map(Resource::id).orElseThrow();

        String itemsId = apigw.createResource(b -> b.restApiId(apiId).parentId(rootId).pathPart("items")).id();
        String itemId = apigw.createResource(b -> b.restApiId(apiId).parentId(itemsId).pathPart("{id}")).id();

        String sfnUri = "arn:aws:apigateway:" + region + ":states:action/StartSyncExecution";

        wireMethod(apigw, apiId, itemsId, "POST", sfnUri, roleArn, createArn,
                "{\"input\": \"$util.escapeJavaScript($input.json('$'))\",\"stateMachineArn\": \"" + createArn + "\"}");
        wireMethod(apigw, apiId, itemsId, "GET", sfnUri, roleArn, listArn,
                "{\"input\": \"{}\",\"stateMachineArn\": \"" + listArn + "\"}");
        wireMethod(apigw, apiId, itemId, "GET", sfnUri, roleArn, readArn,
                "{\"input\": \"{\\\"id\\\": \\\"$input.params('id')\\\"}\",\"stateMachineArn\": \"" + readArn + "\"}");
        wireMethod(apigw, apiId, itemId, "PUT", sfnUri, roleArn, updateArn,
                "#set($b = $input.path('$'))\n{\"input\": \"{\\\"id\\\": \\\"$input.params('id')\\\","
                        + "\\\"name\\\": \\\"$b.name\\\",\\\"value\\\": \\\"$b.value\\\"}\","
                        + "\"stateMachineArn\": \"" + updateArn + "\"}");
        wireMethod(apigw, apiId, itemId, "DELETE", sfnUri, roleArn, deleteArn,
                "{\"input\": \"{\\\"id\\\": \\\"$input.params('id')\\\"}\",\"stateMachineArn\": \"" + deleteArn + "\"}");

        return apiId;
    }

    private static void wireMethod(ApiGatewayClient apigw, String apiId, String resourceId,
                                    String httpMethod, String uri, String roleArn,
                                    String smArn, String reqTemplate) {
        apigw.putMethod(b -> b.restApiId(apiId).resourceId(resourceId)
                .httpMethod(httpMethod).authorizationType("NONE"));
        apigw.putIntegration(b -> b.restApiId(apiId).resourceId(resourceId)
                .httpMethod(httpMethod).type(IntegrationType.AWS)
                .integrationHttpMethod("POST").uri(uri).credentials(roleArn)
                .requestTemplates(Map.of("application/json", reqTemplate)));
        apigw.putMethodResponse(b -> b.restApiId(apiId).resourceId(resourceId)
                .httpMethod(httpMethod).statusCode("200"));
        apigw.putIntegrationResponse(b -> b.restApiId(apiId).resourceId(resourceId)
                .httpMethod(httpMethod).statusCode("200")
                .responseTemplates(Map.of("application/json", "$input.path('$.output')")));
    }

    // HTTP helpers

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(HttpClient http, String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .DELETE().timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
