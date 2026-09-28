package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayOpenApiAwsVariablesIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URI =
            "arn:${AWS::Partition}:apigateway:${AWS::Region}:states:action/StartExecution";
    private static final String CREDENTIALS =
            "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/example-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void importRestApiResolvesVariablesAndInvokesStateMachine() throws Exception {
        ObjectNode stateMachine = JSON.createObjectNode();
        stateMachine.put("name", "apigw-variables-test");
        stateMachine.put("definition", "{\"StartAt\":\"Echo\",\"States\":{\"Echo\":{\"Type\":\"Pass\",\"End\":true}}}");
        stateMachine.put("roleArn", "arn:aws:iam::000000000000:role/example-role");
        String stateMachineArn = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType("application/x-amz-json-1.0")
                .body(stateMachine.toString())
                .when().post("/")
                .then().statusCode(200)
                .extract().path("stateMachineArn");

        String template = "{\"stateMachineArn\":\"" + stateMachineArn + "\",\"input\":\"{}\"}";
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec("VariableImport", URI, CREDENTIALS, template))
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String resourceId = startResourceId(apiId, null);
        String integration = given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(200)
                .extract().asString();
        JsonNode imported = JSON.readTree(integration);
        assertEquals("arn:aws:apigateway:us-east-1:states:action/StartExecution",
                imported.path("uri").asText());
        assertEquals("arn:aws:iam::000000000000:role/example-role",
                imported.path("credentials").asText());

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);
        String response = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/execute-api/" + apiId + "/test/start")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(JSON.readTree(response).path("executionArn").asText().contains("apigw-variables-test"));
    }

    @Test
    void putRestApiUsesRequestAccountAndPartition() throws Exception {
        String authorization = "AWS4-HMAC-SHA256 Credential=000000000001/20260928/us-gov-west-1/apigateway/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        String apiId = given()
                .header("Authorization", authorization)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"variable-overwrite\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", authorization)
                .contentType(ContentType.JSON)
                .queryParam("mode", "overwrite")
                .body(spec("VariableOverwrite", URI, CREDENTIALS,
                        "{\"region\":\"${AWS::Region}\"}"))
                .when().put("/restapis/" + apiId)
                .then().statusCode(200);

        String resourceId = startResourceId(apiId, authorization);
        String integration = given()
                .header("Authorization", authorization)
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(200)
                .extract().asString();
        JsonNode imported = JSON.readTree(integration);
        assertEquals("arn:aws-us-gov:apigateway:us-gov-west-1:states:action/StartExecution",
                imported.path("uri").asText());
        assertEquals("arn:aws-us-gov:iam::000000000001:role/example-role",
                imported.path("credentials").asText());
        assertEquals("{\"region\":\"us-gov-west-1\"}", imported.path("requestTemplates")
                .path("application/json").asText());
    }

    @Test
    void importRestApiResolvesVariablesAcrossDefinition() throws Exception {
        String requestTemplate = "{\"region\":\"${AWS::Region}\"}";
        String responseTemplate = "{\"account\":\"${AWS::AccountId}\"}";
        ObjectNode definition = (ObjectNode) JSON.readTree(spec("DefinitionVariables", URI, CREDENTIALS,
                requestTemplate));
        ObjectNode integration = (ObjectNode) definition.path("paths").path("/start").path("post")
                .path("x-amazon-apigateway-integration");
        integration.putObject("requestParameters")
                .put("integration.request.header.X-Region", "'${AWS::Region}'");
        ((ObjectNode) integration.path("responses").path("default"))
                .putObject("responseTemplates").put("application/json", responseTemplate);
        definition.putObject("x-amazon-apigateway-gateway-responses")
                .putObject("UNAUTHORIZED").putObject("responseTemplates")
                .put("application/json", "{\"partition\":\"${AWS::Partition}\"}");

        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(definition.toString())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String resourceId = startResourceId(apiId, null);

        String importedIntegration = given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(200)
                .extract().asString();
        JsonNode imported = JSON.readTree(importedIntegration);
        assertEquals("{\"region\":\"us-east-1\"}", imported.path("requestTemplates")
                .path("application/json").asText());
        assertEquals("'us-east-1'", imported.path("requestParameters")
                .path("integration.request.header.X-Region").asText());

        String importedResponse = given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(200)
                .extract().asString();
        assertEquals("{\"account\":\"000000000000\"}", JSON.readTree(importedResponse)
                .path("responseTemplates").path("application/json").asText());

        String gatewayResponse = given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/UNAUTHORIZED")
                .then().statusCode(200)
                .extract().asString();
        assertEquals("{\"partition\":\"aws\"}", JSON.readTree(gatewayResponse)
                .path("responseTemplates").path("application/json").asText());
    }

    @Test
    void importRestApiResolvesAuthorizerArns() throws Exception {
        ObjectNode definition = (ObjectNode) JSON.readTree(spec("AuthorizerVariables", URI, CREDENTIALS, "{}"));
        ObjectNode schemes = definition.putObject("components").putObject("securitySchemes");
        ObjectNode cognito = schemes.putObject("CognitoAuth");
        cognito.put("type", "apiKey").put("name", "Authorization").put("in", "header");
        cognito.put("x-amazon-apigateway-authtype", "cognito_user_pools");
        cognito.putObject("x-amazon-apigateway-authorizer").put("type", "cognito_user_pools")
                .putArray("providerARNs").add("arn:${AWS::Partition}:cognito-idp:${AWS::Region}:${AWS::AccountId}:userpool/us-east-1_TEST");
        ObjectNode lambda = schemes.putObject("LambdaAuth");
        lambda.put("type", "apiKey").put("name", "Authorization").put("in", "header");
        lambda.put("x-amazon-apigateway-authtype", "custom");
        lambda.putObject("x-amazon-apigateway-authorizer").put("type", "token")
                .put("authorizerUri", "arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:auth/invocations");

        String apiId = given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body(definition.toString()).when().post("/restapis").then().statusCode(201).extract().path("id");
        JsonNode items = JSON.readTree(given().when().get("/restapis/" + apiId + "/authorizers")
                .then().statusCode(200).extract().asString()).path("item");
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_TEST",
                findByName(items, "CognitoAuth").path("providerARNs").path(0).asText());
        assertEquals("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations",
                findByName(items, "LambdaAuth").path("authorizerUri").asText());
    }

    private static JsonNode findByName(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (name.equals(item.path("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("Authorizer was not found: " + name);
    }

    private static String startResourceId(String apiId, String authorization) throws Exception {
        String resources = authorization == null
                ? given().when().get("/restapis/" + apiId + "/resources")
                        .then().statusCode(200).extract().asString()
                : given().header("Authorization", authorization)
                        .when().get("/restapis/" + apiId + "/resources")
                        .then().statusCode(200).extract().asString();
        for (JsonNode resource : JSON.readTree(resources).path("item")) {
            if ("/start".equals(resource.path("path").asText())) {
                return resource.path("id").asText();
            }
        }
        throw new AssertionError("Imported /start resource was not found");
    }

    private static String spec(String title, String uri, String credentials, String template) {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", "3.0.1");
        root.putObject("info").put("title", title).put("version", "1.0");
        ObjectNode operation = root.putObject("paths").putObject("/start").putObject("post");
        operation.putObject("responses").putObject("200").put("description", "ok");
        ObjectNode integration = operation.putObject("x-amazon-apigateway-integration");
        integration.put("type", "aws");
        integration.put("httpMethod", "POST");
        integration.put("uri", uri);
        integration.put("credentials", credentials);
        integration.putObject("requestTemplates").put("application/json", template);
        integration.putObject("responses").putObject("default").put("statusCode", "200");
        return root.toString();
    }
}
