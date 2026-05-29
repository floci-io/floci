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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for REQUEST authorizer full event shape (issue #807).
 *
 * <p>Validates that {@code toAuthorizerEvent} populates the complete
 * {@code APIGatewayRequestAuthorizerEvent} shape for REQUEST-type authorizers,
 * and that TOKEN and NONE authorizer paths are unaffected.
 *
 * <p>All management-plane setup uses RestAssured against the local Floci endpoint.
 * Execute-api invocations also use RestAssured.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayRequestAuthorizerIntegrationTest {

    private static final String LAMBDA_BASE_PATH = "/2015-03-31/functions";
    private static final String AUTHORIZER_FUNCTION = "apigw-request-auth-echo";
    private static final String PROXY_FUNCTION = "apigw-request-auth-proxy";
    private static final String TOKEN_AUTHORIZER_FUNCTION = "apigw-token-auth-echo";
    private static final String TOKEN_PROXY_FUNCTION = "apigw-token-auth-proxy";
    private static final String NONE_INTEGRATION_FUNCTION = "apigw-none-auth-integration";
    private static final String DENY_AUTHORIZER_FUNCTION = "apigw-deny-auth";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // State shared across ordered tests
    private static String apiId;
    private static String rootId;
    private static String itemResourceId;
    private static String authorizerId;

    private static String tokenApiId;
    private static String noneApiId;
    private static String denyApiId;

    // ──────────────────────────── Lambda setup ────────────────────────────

    @Test
    @Order(1)
    void createEchoAuthorizerLambda() throws Exception {
        createNodeLambda(AUTHORIZER_FUNCTION, """
                exports.handler = async (event) => ({
                  principalId: "test-user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{
                      Action: "execute-api:Invoke",
                      Effect: "Allow",
                      Resource: event.methodArn
                    }]
                  },
                  context: {
                    receivedEvent: JSON.stringify(event)
                  }
                });
                """);
    }

    @Test
    @Order(2)
    void createProxyLambda() throws Exception {
        createNodeLambda(PROXY_FUNCTION, """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({
                    authorizer: event.requestContext && event.requestContext.authorizer
                      ? event.requestContext.authorizer
                      : null
                  })
                });
                """);
    }

    // ──────────────────────────── REQUEST authorizer API setup ────────────────────────────

    @Test
    @Order(3)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"request-authorizer-test-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test
    @Order(4)
    void getResources() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        assertNotNull(rootId);
    }

    @Test
    @Order(5)
    void createResources() {
        String itemsId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"items\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        itemResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{id}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + itemsId)
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(itemResourceId);
    }

    @Test
    @Order(6)
    void createRequestAuthorizer() {
        String authorizerUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + AUTHORIZER_FUNCTION + "/invocations";
        authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"request-echo-authorizer","type":"REQUEST","authorizerUri":"%s","identitySource":"method.request.header.Authorization","authorizerResultTtlInSeconds":0}
                        """.formatted(authorizerUri))
                .when().post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(authorizerId);
    }

    @Test
    @Order(7)
    void configureMethodAndIntegration() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(authorizerId))
                .when().put("/restapis/" + apiId + "/resources/" + itemResourceId + "/methods/GET")
                .then().statusCode(201);

        String proxyUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + PROXY_FUNCTION + "/invocations";
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(proxyUri))
                .when().put("/restapis/" + apiId + "/resources/" + itemResourceId + "/methods/GET/integration")
                .then().statusCode(201);
    }

    @Test
    @Order(8)
    void deployApi() {
        String depId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"request-auth-test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    // ──────────────────────────── Core bug condition test ────────────────────────────

    @Test
    @Order(9)
    void requestAuthorizerReceivesFullEvent() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .when().get("/execute-api/" + apiId + "/prod/items/42?foo=bar")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        JsonNode authorizer = payload.path("authorizer");

        assertFalse(authorizer.isNull(), "authorizer context should be present in proxy response");
        assertFalse(authorizer.isMissingNode(), "authorizer context should be present in proxy response");

        String receivedEventStr = authorizer.path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr, "authorizer should have embedded receivedEvent in context");
        assertFalse(receivedEventStr.isEmpty(), "receivedEvent should not be empty");

        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);

        // type and methodArn
        assertEquals("REQUEST", event.path("type").asText(null));
        assertFalse(event.path("methodArn").isMissingNode(), "methodArn must be present");
        assertTrue(event.path("methodArn").asText("").contains(apiId), "methodArn must contain apiId");

        // resource (matched template path) and actual path
        assertEquals("/items/{id}", event.path("resource").asText(null),
                "resource should be the matched resource path template");
        assertEquals("/items/42", event.path("path").asText(null),
                "path should be the actual request path");
        assertEquals("GET", event.path("httpMethod").asText(null));

        // headers
        assertFalse(event.path("headers").isNull(), "headers must be non-null");
        assertFalse(event.path("headers").isMissingNode(), "headers must be present");

        // multiValueHeaders
        assertFalse(event.path("multiValueHeaders").isNull(), "multiValueHeaders must be non-null");
        assertFalse(event.path("multiValueHeaders").isMissingNode(), "multiValueHeaders must be present");

        // queryStringParameters
        assertFalse(event.path("queryStringParameters").isNull(),
                "queryStringParameters must be non-null when query params are present");
        assertEquals("bar", event.path("queryStringParameters").path("foo").asText(null));

        // multiValueQueryStringParameters
        assertFalse(event.path("multiValueQueryStringParameters").isNull(),
                "multiValueQueryStringParameters must be non-null when query params are present");
        assertTrue(event.path("multiValueQueryStringParameters").path("foo").isArray());

        // pathParameters
        assertFalse(event.path("pathParameters").isNull(), "pathParameters must be non-null");
        assertEquals("42", event.path("pathParameters").path("id").asText(null));

        // stageVariables must be null (no stage variables configured)
        assertTrue(event.path("stageVariables").isNull(), "stageVariables must be null");

        // requestContext
        JsonNode ctx = event.path("requestContext");
        assertFalse(ctx.isNull(), "requestContext must be present");
        assertFalse(ctx.isMissingNode(), "requestContext must be present");

        String requestId = ctx.path("requestId").asText(null);
        assertNotNull(requestId, "requestContext.requestId must be non-null");
        assertFalse(requestId.isEmpty(), "requestContext.requestId must be non-empty");

        assertEquals("127.0.0.1", ctx.path("identity").path("sourceIp").asText(null),
                "requestContext.identity.sourceIp must equal '127.0.0.1'");
        assertFalse(ctx.path("identity").path("userAgent").isMissingNode(),
                "requestContext.identity.userAgent must be present");
        assertTrue(ctx.path("identity").path("apiKey").isNull(),
                "requestContext.identity.apiKey must be null when no matching usage plan key exists");
        assertTrue(ctx.path("identity").path("clientCert").isNull(),
                "requestContext.identity.clientCert must be null (mTLS not supported)");
        assertEquals("/items/{id}", ctx.path("resourcePath").asText(null));
        assertEquals("/items/42", ctx.path("path").asText(null),
                "requestContext.path must equal the actual request path");
        assertEquals("GET", ctx.path("httpMethod").asText(null));
        assertEquals("prod", ctx.path("stage").asText(null));
        assertEquals(ACCOUNT, ctx.path("accountId").asText(null),
                "requestContext.accountId must be present");
        assertEquals(apiId, ctx.path("apiId").asText(null),
                "requestContext.apiId must be present");

        String resourceId = ctx.path("resourceId").asText(null);
        assertNotNull(resourceId, "requestContext.resourceId must be non-null");
        assertFalse(resourceId.isEmpty(), "requestContext.resourceId must be non-empty");
    }

    @Test
    @Order(10)
    void requestAuthorizerNullQueryParamsWhenNonePresent() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .when().get("/execute-api/" + apiId + "/prod/items/99")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr);

        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);
        assertTrue(event.path("queryStringParameters").isNull(),
                "queryStringParameters must be null when no query string is present");
        assertTrue(event.path("multiValueQueryStringParameters").isNull(),
                "multiValueQueryStringParameters must be null when no query string is present");
    }

    // ──────────────────────────── TOKEN authorizer preservation ────────────────────────────

    @Test
    @Order(20)
    void preservation_createTokenLambdas() throws Exception {
        createNodeLambda(TOKEN_AUTHORIZER_FUNCTION, """
                exports.handler = async (event) => ({
                  principalId: "test-user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{
                      Action: "execute-api:Invoke",
                      Effect: "Allow",
                      Resource: event.methodArn
                    }]
                  },
                  context: { receivedEvent: JSON.stringify(event) }
                });
                """);
        createNodeLambda(TOKEN_PROXY_FUNCTION, """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({
                    authorizer: event.requestContext && event.requestContext.authorizer
                      ? event.requestContext.authorizer : null
                  })
                });
                """);
    }

    @Test
    @Order(21)
    void preservation_setupTokenAuthorizerApi() {
        tokenApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"token-authorizer-preservation-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String tokenRootId = given()
                .when().get("/restapis/" + tokenApiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String secureResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"secure\"}")
                .when().post("/restapis/" + tokenApiId + "/resources/" + tokenRootId)
                .then().statusCode(201)
                .extract().path("id");

        String authUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + TOKEN_AUTHORIZER_FUNCTION + "/invocations";
        String tokenAuthId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"token-echo-authorizer","type":"TOKEN","authorizerUri":"%s","identitySource":"method.request.header.Authorization","authorizerResultTtlInSeconds":0}
                        """.formatted(authUri))
                .when().post("/restapis/" + tokenApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(tokenAuthId))
                .when().put("/restapis/" + tokenApiId + "/resources/" + secureResourceId + "/methods/GET")
                .then().statusCode(201);

        String proxyUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + TOKEN_PROXY_FUNCTION + "/invocations";
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(proxyUri))
                .when().put("/restapis/" + tokenApiId + "/resources/" + secureResourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String depId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"token-auth-preservation\"}")
                .when().post("/restapis/" + tokenApiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + tokenApiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(22)
    void preservation_tokenAuthorizerEventShapeIsExact() throws Exception {
        String response = given()
                .header("Authorization", "Bearer mytoken")
                .when().get("/execute-api/" + tokenApiId + "/prod/secure")
                .then().statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr, "TOKEN authorizer should embed receivedEvent in context");

        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);

        assertEquals("TOKEN", event.path("type").asText(null));
        assertFalse(event.path("methodArn").isMissingNode(), "methodArn must be present");
        assertEquals("Bearer mytoken", event.path("authorizationToken").asText(null));

        // TOKEN event must NOT contain REQUEST-specific fields
        assertTrue(event.path("headers").isMissingNode() || event.path("headers").isNull(),
                "TOKEN event must NOT contain headers");
        assertTrue(event.path("queryStringParameters").isMissingNode() || event.path("queryStringParameters").isNull(),
                "TOKEN event must NOT contain queryStringParameters");
        assertTrue(event.path("requestContext").isMissingNode() || event.path("requestContext").isNull(),
                "TOKEN event must NOT contain requestContext");
    }

    // ──────────────────────────── NONE authorizer preservation ────────────────────────────

    @Test
    @Order(30)
    void preservation_setupNoneAuthorizerApi() throws Exception {
        createNodeLambda(NONE_INTEGRATION_FUNCTION, """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({ invoked: true })
                });
                """);

        noneApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"none-authorizer-preservation-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String noneRootId = given()
                .when().get("/restapis/" + noneApiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String openResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"open\"}")
                .when().post("/restapis/" + noneApiId + "/resources/" + noneRootId)
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + noneApiId + "/resources/" + openResourceId + "/methods/GET")
                .then().statusCode(201);

        String integrationUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + NONE_INTEGRATION_FUNCTION + "/invocations";
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(integrationUri))
                .when().put("/restapis/" + noneApiId + "/resources/" + openResourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String depId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"none-auth-preservation\"}")
                .when().post("/restapis/" + noneApiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + noneApiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(31)
    void preservation_noneAuthorizerSkipsInvocation() {
        given()
                .when().get("/execute-api/" + noneApiId + "/prod/open")
                .then()
                .statusCode(200)
                .body("invoked", org.hamcrest.Matchers.equalTo(true));
    }

    // ──────────────────────────── Allow / Deny policy ────────────────────────────

    @Test
    @Order(32)
    void preservation_requestAuthorizerAllowPolicyForwardsToIntegration() {
        given()
                .header("Authorization", "Bearer test")
                .when().get("/execute-api/" + apiId + "/prod/items/42")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(40)
    void preservation_setupDenyAuthorizerApi() throws Exception {
        createNodeLambda(DENY_AUTHORIZER_FUNCTION, """
                exports.handler = async (event) => ({
                  principalId: "test-user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{
                      Action: "execute-api:Invoke",
                      Effect: "Deny",
                      Resource: event.methodArn
                    }]
                  }
                });
                """);

        denyApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"deny-authorizer-preservation-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String denyRootId = given()
                .when().get("/restapis/" + denyApiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String protectedResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"protected\"}")
                .when().post("/restapis/" + denyApiId + "/resources/" + denyRootId)
                .then().statusCode(201)
                .extract().path("id");

        String authUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + DENY_AUTHORIZER_FUNCTION + "/invocations";
        String denyAuthId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"deny-authorizer","type":"REQUEST","authorizerUri":"%s","identitySource":"method.request.header.Authorization","authorizerResultTtlInSeconds":0}
                        """.formatted(authUri))
                .when().post("/restapis/" + denyApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(denyAuthId))
                .when().put("/restapis/" + denyApiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + denyApiId + "/resources/" + protectedResourceId + "/methods/GET/integration")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + denyApiId + "/resources/" + protectedResourceId + "/methods/GET/responses/200")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"ok\\\"}\"}}")
                .when().put("/restapis/" + denyApiId + "/resources/" + protectedResourceId + "/methods/GET/integration/responses/200")
                .then().statusCode(201);

        String depId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"deny-auth-preservation\"}")
                .when().post("/restapis/" + denyApiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + denyApiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(41)
    void preservation_requestAuthorizerDenyPolicyReturns403() {
        given()
                .header("Authorization", "Bearer anytoken")
                .when().get("/execute-api/" + denyApiId + "/prod/protected")
                .then()
                .statusCode(403);
    }

    // ──────────────────────────── Stage Variables ────────────────────────────

    private static String stageVarApiId;

    @Test
    @Order(50)
    void stageVariables_setupApiWithStageVars() throws Exception {
        createNodeLambda("apigw-stagevar-auth-echo", """
                exports.handler = async (event) => ({
                  principalId: "user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn }]
                  },
                  context: { receivedEvent: JSON.stringify(event) }
                });
                """);
        createNodeLambda("apigw-stagevar-proxy", """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({
                    stageVariables: event.stageVariables,
                    authorizer: event.requestContext && event.requestContext.authorizer
                      ? event.requestContext.authorizer : null
                  })
                });
                """);

        stageVarApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"stage-var-test-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String svRootId = given()
                .when().get("/restapis/" + stageVarApiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"ping\"}")
                .when().post("/restapis/" + stageVarApiId + "/resources/" + svRootId)
                .then().statusCode(201)
                .extract().path("id");

        String authUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:apigw-stagevar-auth-echo/invocations";
        String svAuthId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"sv-auth","type":"REQUEST","authorizerUri":"%s","identitySource":"method.request.header.Authorization","authorizerResultTtlInSeconds":0}
                        """.formatted(authUri))
                .when().post("/restapis/" + stageVarApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(svAuthId))
                .when().put("/restapis/" + stageVarApiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        String proxyUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:apigw-stagevar-proxy/invocations";
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(proxyUri))
                .when().put("/restapis/" + stageVarApiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String depId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"sv-test\"}")
                .when().post("/restapis/" + stageVarApiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        // Create stage WITH variables
        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s","variables":{"env":"test","version":"v1"}}
                        """.formatted(depId))
                .when().post("/restapis/" + stageVarApiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(51)
    void stageVariables_authorizerEventContainsStageVars() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .when().get("/execute-api/" + stageVarApiId + "/prod/ping")
                .then().statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);

        // Proxy event stageVariables
        JsonNode proxyStageVars = payload.path("stageVariables");
        assertFalse(proxyStageVars.isNull(), "proxy event stageVariables must be non-null");
        assertEquals("test", proxyStageVars.path("env").asText(null));
        assertEquals("v1", proxyStageVars.path("version").asText(null));

        // Authorizer event stageVariables (embedded in context)
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr);
        JsonNode authEvent = OBJECT_MAPPER.readTree(receivedEventStr);
        JsonNode authStageVars = authEvent.path("stageVariables");
        assertFalse(authStageVars.isNull(), "authorizer event stageVariables must be non-null");
        assertEquals("test", authStageVars.path("env").asText(null));
        assertEquals("v1", authStageVars.path("version").asText(null));
    }

    // ──────────────────────────── API Key Resolution ────────────────────────────

    private static String apiKeyApiId;

    @Test
    @Order(60)
    void apiKey_setupApiWithUsagePlan() throws Exception {
        createNodeLambda("apigw-apikey-auth-echo", """
                exports.handler = async (event) => ({
                  principalId: "user",
                  policyDocument: {
                    Version: "2012-10-17",
                    Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn }]
                  },
                  context: { receivedEvent: JSON.stringify(event) }
                });
                """);
        createNodeLambda("apigw-apikey-proxy", """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({
                    authorizer: event.requestContext && event.requestContext.authorizer
                      ? event.requestContext.authorizer : null
                  })
                });
                """);

        apiKeyApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"apikey-test-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String akRootId = given()
                .when().get("/restapis/" + apiKeyApiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"secure\"}")
                .when().post("/restapis/" + apiKeyApiId + "/resources/" + akRootId)
                .then().statusCode(201)
                .extract().path("id");

        String authUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:apigw-apikey-auth-echo/invocations";
        String akAuthId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ak-auth","type":"REQUEST","authorizerUri":"%s","identitySource":"method.request.header.Authorization","authorizerResultTtlInSeconds":0}
                        """.formatted(authUri))
                .when().post("/restapis/" + apiKeyApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(akAuthId))
                .when().put("/restapis/" + apiKeyApiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        String proxyUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:apigw-apikey-proxy/invocations";
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(proxyUri))
                .when().put("/restapis/" + apiKeyApiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String depId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"ak-test\"}")
                .when().post("/restapis/" + apiKeyApiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + apiKeyApiId + "/stages")
                .then().statusCode(201);

        // Create API key + usage plan linked to this (apiId, stage)
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"test-key\",\"value\":\"my-secret-api-key\",\"enabled\":true}")
                .when().post("/apikeys")
                .then().statusCode(201);

        String keyId = given()
                .when().get("/apikeys")
                .then().statusCode(200)
                .extract().path("item.find { it.name == 'test-key' }.id");

        String planId = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"test-plan","apiStages":[{"apiId":"%s","stage":"prod"}]}
                        """.formatted(apiKeyApiId))
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"keyId":"%s","keyType":"API_KEY"}
                        """.formatted(keyId))
                .when().post("/usageplans/" + planId + "/keys")
                .then().statusCode(201);
    }

    @Test
    @Order(61)
    void apiKey_identityApiKeyPopulatedWhenHeaderMatches() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .header("x-api-key", "my-secret-api-key")
                .when().get("/execute-api/" + apiKeyApiId + "/prod/secure")
                .then().statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr);
        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);

        assertEquals("my-secret-api-key",
                event.path("requestContext").path("identity").path("apiKey").asText(null),
                "identity.apiKey must equal the matched usage plan key value");
    }

    @Test
    @Order(62)
    void apiKey_identityApiKeyNullWhenNoHeaderPresent() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .when().get("/execute-api/" + apiKeyApiId + "/prod/secure")
                .then().statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr);
        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);

        assertTrue(event.path("requestContext").path("identity").path("apiKey").isNull(),
                "identity.apiKey must be null when no x-api-key header is present");
    }

    @Test
    @Order(63)
    void apiKey_identityApiKeyNullWhenHeaderDoesNotMatchAnyPlanKey() throws Exception {
        String response = given()
                .header("Authorization", "Bearer test")
                .header("x-api-key", "wrong-key-value")
                .when().get("/execute-api/" + apiKeyApiId + "/prod/secure")
                .then().statusCode(200)
                .extract().asString();

        JsonNode payload = OBJECT_MAPPER.readTree(response);
        String receivedEventStr = payload.path("authorizer").path("receivedEvent").asText(null);
        assertNotNull(receivedEventStr);
        JsonNode event = OBJECT_MAPPER.readTree(receivedEventStr);

        assertTrue(event.path("requestContext").path("identity").path("apiKey").isNull(),
                "identity.apiKey must be null when x-api-key header does not match any plan key");
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (apiId != null) given().when().delete("/restapis/" + apiId).then().statusCode(202);
        if (tokenApiId != null) given().when().delete("/restapis/" + tokenApiId).then().statusCode(202);
        if (noneApiId != null) given().when().delete("/restapis/" + noneApiId).then().statusCode(202);
        if (denyApiId != null) given().when().delete("/restapis/" + denyApiId).then().statusCode(202);
        if (stageVarApiId != null) given().when().delete("/restapis/" + stageVarApiId).then().statusCode(202);
        if (apiKeyApiId != null) given().when().delete("/restapis/" + apiKeyApiId).then().statusCode(202);
        deleteFunction(AUTHORIZER_FUNCTION);
        deleteFunction(PROXY_FUNCTION);
        deleteFunction(TOKEN_AUTHORIZER_FUNCTION);
        deleteFunction(TOKEN_PROXY_FUNCTION);
        deleteFunction(NONE_INTEGRATION_FUNCTION);
        deleteFunction(DENY_AUTHORIZER_FUNCTION);
        deleteFunction("apigw-stagevar-auth-echo");
        deleteFunction("apigw-stagevar-proxy");
        deleteFunction("apigw-apikey-auth-echo");
        deleteFunction("apigw-apikey-proxy");
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static void createNodeLambda(String functionName, String handlerSource) throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of(
                "index.js", handlerSource
        )));
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "FunctionName": "%s",
                          "Runtime": "nodejs20.x",
                          "Role": "%s",
                          "Handler": "index.handler",
                          "Timeout": 30,
                          "Code": {"ZipFile": "%s"}
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
