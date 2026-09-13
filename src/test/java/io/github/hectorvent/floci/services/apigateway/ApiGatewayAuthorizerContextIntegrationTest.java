package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayAuthorizerContextIntegrationTest {

    private static final String LAMBDA_BASE_PATH = "/2015-03-31/functions";
    private static final String AUTHORIZER_FUNCTION = "apigw-authorizer-context-auth";
    private static final String PROXY_FUNCTION = "apigw-authorizer-context-proxy";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lambda-role";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String apiId;
    private static String rootId;
    private static String securedResourceId;
    private static String plainResourceId;
    private static String mappedResourceId;
    private static String authorizerId;
    private static String deploymentId;

    @Test
    @Order(1)
    void createAuthorizerLambda() throws Exception {
        createNodeLambda(AUTHORIZER_FUNCTION, """
                exports.handler = async (event) => ({
                  principalId: "test-user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{
                      Action: "execute-api:Invoke",
                      Effect: event.authorizationToken === "Bearer deny" ? "Deny" : "Allow",
                      Resource: event.methodArn
                    }]
                  },
                  context: {
                    org_id: "ORG001",
                    sub: "test-user",
                    client_id: "my-client",
                    key_id: "KEY-123",
                    numKey: 1,
                    boolKey: true,
                    identity: JSON.stringify({key_id: "KEY-123", tenant: "t-9"}),
                    methodArn: event.methodArn
                  }
                });
                """);
    }

    @Test
    @Order(2)
    void createProxyLambda() throws Exception {
        createNodeLambda(PROXY_FUNCTION, """
                exports.handler = async (event) => {
                  if (!event.requestContext) {
                    return event;
                  }
                  return {
                    statusCode: 200,
                    body: JSON.stringify({
                      authorizer: event.requestContext.authorizer ?? null,
                      hasAuthorizer: Object.prototype.hasOwnProperty.call(event.requestContext, "authorizer")
                    })
                  };
                };
                """);
    }

    @Test
    @Order(3)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authorizer-context-api"}
                        """)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    @Test
    @Order(4)
    void getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");
    }

    @Test
    @Order(5)
    void createResources() {
        securedResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"secured\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        plainResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"plain\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        mappedResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"mapped\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    @Test
    @Order(6)
    void createAuthorizer() {
        String authorizerUri = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:us-east-1:000000000000:function:" + AUTHORIZER_FUNCTION + "/invocations";
        authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"ctx-authorizer",
                          "type":"TOKEN",
                          "authorizerUri":"%s",
                          "identitySource":"method.request.header.Authorization",
                          "authorizerResultTtlInSeconds":0
                        }
                        """.formatted(authorizerUri))
                .when().post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    @Test
    @Order(7)
    void configureMethodsAndIntegrations() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizationType":"CUSTOM",
                          "authorizerId":"%s",
                          "requestParameters":{"method.request.header.Authorization":true}
                        }
                        """.formatted(authorizerId))
                .when().put("/restapis/" + apiId + "/resources/" + securedResourceId + "/methods/PUT")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizationType":"CUSTOM",
                          "authorizerId":"%s",
                          "requestParameters":{"method.request.header.Authorization":true}
                        }
                        """.formatted(authorizerId))
                .when().put("/restapis/" + apiId + "/resources/" + mappedResourceId + "/methods/PUT")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"NONE"}
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + plainResourceId + "/methods/PUT")
                .then()
                .statusCode(201);

        String proxyUri = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:us-east-1:000000000000:function:" + PROXY_FUNCTION + "/invocations";
        String integrationBody = """
                {
                  "type":"AWS_PROXY",
                  "httpMethod":"POST",
                  "uri":"%s"
                }
                """.formatted(proxyUri);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + securedResourceId + "/methods/PUT/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + plainResourceId + "/methods/PUT/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "type":"AWS",
                          "httpMethod":"POST",
                          "uri":"%s",
                          "requestTemplates":{
                            "application/json":"#set($identity = $util.parseJson($context.authorizer.identity))#set($numberIsString = $context.authorizer.numKey == \\\"1\\\")#set($booleanIsString = $context.authorizer.boolKey == \\\"true\\\"){\\\"principalId\\\":\\\"$context.authorizer.principalId\\\",\\\"numberIsString\\\":$numberIsString,\\\"booleanIsString\\\":$booleanIsString,\\\"keyId\\\":\\\"$context.authorizer.key_id\\\",\\\"tenant\\\":\\\"$identity.tenant\\\"}"
                          }
                        }
                        """.formatted(proxyUri))
                .when().put("/restapis/" + apiId + "/resources/" + mappedResourceId + "/methods/PUT/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "selectionPattern":"",
                          "responseTemplates":{
                            "application/json":"#set($numberIsString = $context.authorizer.numKey == \\\"1\\\")#set($booleanIsString = $context.authorizer.boolKey == \\\"true\\\"){\\\"request\\\":$input.json('$'),\\\"principalId\\\":\\\"$context.authorizer.principalId\\\",\\\"numberIsString\\\":$numberIsString,\\\"booleanIsString\\\":$booleanIsString}"
                          }
                        }
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + mappedResourceId
                        + "/methods/PUT/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(8)
    void deployApi() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"authorizer-context\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test","deploymentId":"%s"}
                        """.formatted(deploymentId))
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(9)
    void executeSecuredRoute_propagatesAuthorizerContextAndMethodArn() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer allow")
                .body("{\"ok\":true}")
                .when().put("/execute-api/" + apiId + "/test/secured")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        JsonNode authorizer = payload.path("authorizer");

        assertTrue(payload.path("hasAuthorizer").asBoolean());
        assertEquals("test-user", authorizer.path("principalId").asText());
        assertEquals("ORG001", authorizer.path("org_id").asText());
        assertEquals("test-user", authorizer.path("sub").asText());
        assertEquals("my-client", authorizer.path("client_id").asText());
        assertEquals(
                "arn:aws:execute-api:us-east-1:000000000000:" + apiId + "/test/PUT/secured",
                authorizer.path("methodArn").asText());
    }

    @Test
    @Order(10)
    void executeMappedRoute_propagatesAuthorizerContextToRequestTemplate() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer allow")
                .body("{\"ok\":true}")
                .when().put("/execute-api/" + apiId + "/test/mapped")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        assertEquals("test-user", payload.path("principalId").asText());
        assertTrue(payload.path("numberIsString").asBoolean());
        assertTrue(payload.path("booleanIsString").asBoolean());
        assertEquals("test-user", payload.path("request").path("principalId").asText());
        assertTrue(payload.path("request").path("numberIsString").asBoolean());
        assertTrue(payload.path("request").path("booleanIsString").asBoolean());
        assertEquals("KEY-123", payload.path("request").path("keyId").asText());
        assertEquals("t-9", payload.path("request").path("tenant").asText());
    }

    @Test
    @Order(11)
    void executePlainRoute_doesNotInjectAuthorizerContext() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .body("{\"ok\":true}")
                .when().put("/execute-api/" + apiId + "/test/plain")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        assertFalse(payload.path("hasAuthorizer").asBoolean());
        assertTrue(payload.path("authorizer").isNull());
    }

    @Test
    @Order(12)
    void executeSecuredRoute_denyStillReturns403() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer deny")
                .body("{\"ok\":true}")
                .when().put("/execute-api/" + apiId + "/test/secured")
                .then()
                .statusCode(403);
    }

    @Test
    @Order(99)
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        deleteFunction(AUTHORIZER_FUNCTION);
        deleteFunction(PROXY_FUNCTION);
    }

    private static void createNodeLambda(String functionName, String handlerSource) throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of(
                "index.js", handlerSource
        )));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs20.x",
                          "Role":"%s",
                          "Handler":"index.handler",
                          "Timeout":30,
                          "Code":{"ZipFile":"%s"}
                        }
                        """.formatted(functionName, ROLE_ARN, zipBase64))
                .when().post(LAMBDA_BASE_PATH)
                .then()
                .statusCode(201);
    }

    private static byte[] zipEntries(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static void deleteFunction(String functionName) {
        int statusCode = given()
                .when().delete(LAMBDA_BASE_PATH + "/" + functionName)
                .then()
                .extract().statusCode();
        assertTrue(statusCode == 204 || statusCode == 404);
    }
}
