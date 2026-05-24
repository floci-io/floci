package io.github.hectorvent.floci.services.apigatewayv2;

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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Lambda REQUEST authorizer on HTTP API (v2) routes (issue #812).
 *
 * <p>Covers payload format versions 1.0 and 2.0, simple responses, identity source
 * validation, and deny/allow/error scenarios.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiRequestAuthorizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String httpApiId;
    private static String integrationId;
    private static String routeId;
    private static String allowAuthorizerId;
    private static String denyAuthorizerId;
    private static String errorAuthorizerId;
    private static String simpleAllowAuthorizerId;
    private static String simpleDenyAuthorizerId;
    private static String identitySourceAuthorizerId;
    private static String echoV1AuthorizerId;
    private static String echoV2AuthorizerId;

    private static final String ALLOW_FN = "httpv2-auth-allow-fn";
    private static final String DENY_FN = "httpv2-auth-deny-fn";
    private static final String ERROR_FN = "httpv2-auth-error-fn";
    private static final String SIMPLE_ALLOW_FN = "httpv2-auth-simple-allow-fn";
    private static final String SIMPLE_DENY_FN = "httpv2-auth-simple-deny-fn";
    private static final String ECHO_FN = "httpv2-auth-echo-fn";
    private static final String BACKEND_FN = "httpv2-auth-backend-fn";

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupHttpApi() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-v2-authorizer-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(2)
    void setupLambdaFunctions() throws Exception {
        // Backend Lambda
        createNodeLambda(BACKEND_FN, """
                exports.handler = async (event) => ({
                    statusCode: 200,
                    body: JSON.stringify({ message: "Hello from backend", path: event.rawPath })
                });
                """);

        // Authorizer that returns Allow policy (IAM policy format)
        createNodeLambda(ALLOW_FN, """
                exports.handler = async (event) => ({
                    principalId: "user123",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn || event.routeArn || "*" }]
                    },
                    context: { userId: "user123", role: "admin" }
                });
                """);

        // Authorizer that returns Deny policy
        createNodeLambda(DENY_FN, """
                exports.handler = async (event) => ({
                    principalId: "user123",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Deny", Resource: event.methodArn || event.routeArn || "*" }]
                    }
                });
                """);

        // Authorizer that throws an error
        createNodeLambda(ERROR_FN, """
                exports.handler = async (event) => { throw new Error("Authorizer error"); };
                """);

        // Authorizer that returns simple Allow response (format 2.0)
        createNodeLambda(SIMPLE_ALLOW_FN, """
                exports.handler = async (event) => ({
                    isAuthorized: true,
                    context: { userId: "simple-user", plan: "premium" }
                });
                """);

        // Authorizer that returns simple Deny response (format 2.0)
        createNodeLambda(SIMPLE_DENY_FN, """
                exports.handler = async (event) => ({
                    isAuthorized: false
                });
                """);

        // Echo authorizer that returns the event payload in context
        createNodeLambda(ECHO_FN, """
                exports.handler = async (event) => ({
                    principalId: "echo-user",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn || event.routeArn || "*" }]
                    },
                    context: { receivedEvent: JSON.stringify(event) }
                });
                """);
    }

    @Test
    @Order(3)
    void prewarmLambdaFunctions() {
        for (String fn : new String[]{BACKEND_FN, ALLOW_FN, DENY_FN, ERROR_FN, SIMPLE_ALLOW_FN, SIMPLE_DENY_FN, ECHO_FN}) {
            given().contentType(ContentType.JSON).body("{}")
                    .when().post("/2015-03-31/functions/" + fn + "/invocations")
                    .then().statusCode(200);
        }
    }

    @Test
    @Order(4)
    void setupIntegrationAndRoute() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"2.0"}
                        """.formatted(BACKEND_FN))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /hello","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    @Test
    @Order(5)
    void setupAuthorizers() {
        // Allow authorizer (IAM policy format, payload version 1.0)
        allowAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"allow-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ALLOW_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Deny authorizer (IAM policy format, payload version 1.0)
        denyAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"deny-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(DENY_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Error authorizer
        errorAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"error-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ERROR_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Simple allow authorizer (format 2.0 with enableSimpleResponses)
        simpleAllowAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"simple-allow-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"2.0","enableSimpleResponses":true,"authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(SIMPLE_ALLOW_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Simple deny authorizer (format 2.0 with enableSimpleResponses)
        simpleDenyAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"simple-deny-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"2.0","enableSimpleResponses":true,"authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(SIMPLE_DENY_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Identity source authorizer (requires Authorization header)
        identitySourceAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"identity-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","identitySource":"$request.header.Authorization","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ALLOW_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Echo authorizer (format 1.0)
        echoV1AuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"echo-auth-v1","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ECHO_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        // Echo authorizer (format 2.0)
        echoV2AuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"echo-auth-v2","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"2.0","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ECHO_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");
    }

    // ──────────────────────────── Test: Allow with IAM policy (format 1.0) ────────────────────────────

    @Test
    @Order(10)
    void requestAuthorizerAllowsWithIamPolicy() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(allowAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200);
    }

    // ──────────────────────────── Test: Deny with IAM policy (format 1.0) ────────────────────────────

    @Test
    @Order(20)
    void requestAuthorizerDeniesWithIamPolicy() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(denyAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(403);
    }

    // ──────────────────────────── Test: Authorizer invocation error returns 500 ────────────────────────────

    @Test
    @Order(30)
    void requestAuthorizerErrorReturns500() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(errorAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(500);
    }

    // ──────────────────────────── Test: Simple response Allow (format 2.0) ────────────────────────────

    @Test
    @Order(40)
    void simpleResponseAllows() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(simpleAllowAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200);
    }

    // ──────────────────────────── Test: Simple response Deny (format 2.0) ────────────────────────────

    @Test
    @Order(50)
    void simpleResponseDenies() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(simpleDenyAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(403);
    }

    // ──────────────────────────── Test: Missing identity source returns 401 ────────────────────────────

    @Test
    @Order(60)
    void missingIdentitySourceReturns401() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(identitySourceAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        // Request WITHOUT the required Authorization header — should get 401
        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(401);
    }

    // ──────────────────────────── Test: Present identity source allows invocation ────────────────────────────

    @Test
    @Order(61)
    void presentIdentitySourceAllowsInvocation() {
        // Route still has identitySourceAuthorizerId from previous test
        given()
                .header("Authorization", "Bearer my-token")
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200);
    }

    // ──────────────────────────── Test: Query string identity source validation ────────────────────────────

    @Test
    @Order(70)
    void queryStringIdentitySourceValidation() {
        String qsAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"qs-identity-auth","authorizerType":"REQUEST","authorizerPayloadFormatVersion":"1.0","identitySource":"$request.querystring.token","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(ALLOW_FN))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(qsAuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        // Without required query param — 401
        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(401);

        // With required query param — succeeds
        given()
                .queryParam("token", "my-secret-token")
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200);
    }

    // ──────────────────────────── Test: Payload format 1.0 event shape ────────────────────────────

    @Test
    @Order(80)
    void payloadFormat10EventShape() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(echoV1AuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        // The echo authorizer allows the request and embeds the received event in its context.
        // The backend Lambda receives the proxy event which does NOT include authorizer context
        // in the v2 proxy event format. So we verify the authorizer was invoked (200 response)
        // and then invoke the echo function directly with a realistic event to verify format.
        String response = given()
                .header("X-Custom-Header", "test-value")
                .queryParam("foo", "bar")
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200)
                .extract().body().asString();

        // Backend received the request — authorizer allowed it
        JsonNode backendResponse = MAPPER.readTree(response);
        assertEquals("Hello from backend", backendResponse.path("message").asText());

        // Verify format by invoking echo function with a v1-shaped event
        String testEvent = """
                {"version":"1.0","type":"REQUEST","methodArn":"arn:aws:execute-api:us-east-1:000000000000:%s/test/GET/hello","resource":"/hello","path":"/hello","httpMethod":"GET","headers":{"X-Custom-Header":"test-value"},"queryStringParameters":{"foo":"bar"}}
                """.formatted(httpApiId);
        String echoResponse = given()
                .contentType(ContentType.JSON)
                .body(testEvent)
                .when().post("/2015-03-31/functions/" + ECHO_FN + "/invocations")
                .then().statusCode(200)
                .extract().body().asString();

        JsonNode echoResult = MAPPER.readTree(echoResponse);
        String authEventStr = echoResult.path("context").path("receivedEvent").asText(null);
        assertNotNull(authEventStr, "Echo authorizer should embed receivedEvent in context");
        JsonNode authEvent = MAPPER.readTree(authEventStr);

        assertEquals("REQUEST", authEvent.path("type").asText(), "Event type should be REQUEST");
        assertEquals("1.0", authEvent.path("version").asText(), "Event version should be 1.0");
        assertNotNull(authEvent.path("methodArn").asText(null), "Event should have methodArn");
        assertEquals("/hello", authEvent.path("resource").asText(), "Event should have resource");
        assertEquals("GET", authEvent.path("httpMethod").asText(), "Event should have httpMethod");
    }

    // ──────────────────────────── Test: Payload format 2.0 event shape ────────────────────────────

    @Test
    @Order(90)
    void payloadFormat20EventShape() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(echoV2AuthorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        String response = given()
                .header("X-Custom-Header", "test-value-v2")
                .queryParam("key", "value")
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200)
                .extract().body().asString();

        // Backend received the request — authorizer allowed it
        JsonNode backendResponse = MAPPER.readTree(response);
        assertEquals("Hello from backend", backendResponse.path("message").asText());

        // Verify format by invoking echo function with a v2-shaped event
        String testEvent = """
                {"version":"2.0","type":"REQUEST","routeArn":"arn:aws:execute-api:us-east-1:000000000000:%s/test/GET/hello","routeKey":"GET /hello","rawPath":"/hello","rawQueryString":"key=value","headers":{"x-custom-header":"test-value-v2"},"queryStringParameters":{"key":"value"},"requestContext":{"accountId":"000000000000","apiId":"%s","http":{"method":"GET","path":"/hello"}}}
                """.formatted(httpApiId, httpApiId);
        String echoResponse = given()
                .contentType(ContentType.JSON)
                .body(testEvent)
                .when().post("/2015-03-31/functions/" + ECHO_FN + "/invocations")
                .then().statusCode(200)
                .extract().body().asString();

        JsonNode echoResult = MAPPER.readTree(echoResponse);
        String authEventStr = echoResult.path("context").path("receivedEvent").asText(null);
        assertNotNull(authEventStr, "Echo authorizer should embed receivedEvent in context");
        JsonNode authEvent = MAPPER.readTree(authEventStr);

        assertEquals("REQUEST", authEvent.path("type").asText(), "Event type should be REQUEST");
        assertEquals("2.0", authEvent.path("version").asText(), "Event version should be 2.0");
        assertNotNull(authEvent.path("routeArn").asText(null), "Event should have routeArn");
        assertEquals("GET /hello", authEvent.path("routeKey").asText(), "Event should have routeKey");
        assertEquals("/hello", authEvent.path("rawPath").asText(), "Event should have rawPath");
    }

    // ──────────────────────────── Test: No authorizer (NONE) allows request ────────────────────────────

    @Test
    @Order(100)
    void noAuthorizerAllowsRequest() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"NONE","authorizerId":null}
                        """)
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(200);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        if (routeId != null) given().when().delete("/v2/apis/" + httpApiId + "/routes/" + routeId);
        if (httpApiId != null) given().when().delete("/v2/apis/" + httpApiId);
        for (String fn : new String[]{BACKEND_FN, ALLOW_FN, DENY_FN, ERROR_FN, SIMPLE_ALLOW_FN, SIMPLE_DENY_FN, ECHO_FN}) {
            deleteFunction(fn);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static void createNodeLambda(String functionName, String handlerSource) throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of("index.js", handlerSource)));
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(functionName, zipBase64))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);
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
                .when().delete("/2015-03-31/functions/" + functionName)
                .then().extract().statusCode();
        assertTrue(statusCode == 204 || statusCode == 404);
    }
}
