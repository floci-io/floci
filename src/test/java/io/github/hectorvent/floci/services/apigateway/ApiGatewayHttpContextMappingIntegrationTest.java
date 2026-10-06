package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayHttpContextMappingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FUNCTION = "http-context-authorizer";
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + FUNCTION + "/invocations";
    private static final AtomicInteger backendRequests = new AtomicInteger();
    private static HttpServer backend;

    @InjectMock
    LambdaService lambdaService;

    private String apiId;
    private String resourceId;
    private String methodPath;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ApiGatewayHttpContextMappingIntegrationTest::echo);
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    private static void echo(HttpExchange exchange) throws IOException {
        backendRequests.incrementAndGet();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
        response.put("headers", headers);
        response.put("path", exchange.getRequestURI().getPath());
        response.put("query", exchange.getRequestURI().getQuery());
        response.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] payload = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @BeforeEach
    void createApi() {
        backendRequests.set(0);
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "http-context-mapping"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", "{proxy+}"))
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/";
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void forwardsVerifiedAuthorizerScalarsAndRequestContext(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "forged", "also-forged")
                    .header("x-missing", "forged-missing")
                    .header("x-object", "forged-object")
                    .header("x-request-id", "client-id")
                    .header("X-Source", "client-value")
                    .get(endpoint + "orders/42?client=acme")
                    .then().statusCode(200).extract().asByteArray());
            assertMappedContext(response);
            assertEquals("/test/orders/42", response.path("headers").path("x-context-path").get(0).asText());
            assertEquals("GET", response.path("headers").path("x-context-method").get(0).asText());
            assertEquals(apiId, response.path("headers").path("x-context-api").get(0).asText());
            assertEquals(resourceId, response.path("headers").path("x-context-resource").get(0).asText());
            assertEquals("/users/verified-user/orders/42", response.path("path").asText());
            assertTrue(response.path("query").asText().contains("claims=verified-claims"));
            assertTrue(response.path("query").asText().contains("client=acme"));
            assertEquals("client-value", response.path("headers").path("x-from-client").get(0).asText());
            assertEquals("static-value", response.path("headers").path("x-static").get(0).asText());
            if ("HTTP".equals(type)) {
                assertEquals(response.path("headers").path("x-request-id").get(0).asText(),
                        MAPPER.readTree(response.path("body").asText()).path("requestId").asText());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void rejectsObjectAuthorizerContextBeforeReachingBackend(String type) throws Exception {
        configureAuthorizer(0, Map.of("objectKey", Map.of("nested", "value")));
        configureIntegration(type, "GET", Map.of(
                "integration.request.header.X-Object", "context.authorizer.objectKey",
                "integration.request.path.principal", "context.authorizer.principalId",
                "integration.request.path.proxy", "method.request.path.proxy"));
        deploy();
        given().header("Authorization", "Bearer allowed").header("X-Object", "forged")
                .get("/execute-api/" + apiId + "/test/orders/42").then().statusCode(500);
        assertEquals(0, backendRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void missingAuthorizerClaimDoesNotFallBackToClientHeader(String type) throws Exception {
        configureAuthorizer(0, Map.of());
        configureIntegration(type, "GET");
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "forged", "also-forged")
                    .get(endpoint + "orders/42")
                    .then().statusCode(200).extract().asByteArray());
            assertFalse(response.path("headers").has("x-user-claims"));
            assertEquals(MAPPER.valueToTree(List.of("verified-user")), response.path("headers").path("x-principal"));
        }
    }

    @Test
    void missingMethodRequestMappingPreservesInboundProxyHeader() throws Exception {
        configureAuthorizer(0);
        configureIntegration("HTTP_PROXY", "GET");
        deploy();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                .header("X-Unresolved", "client-value")
                .get("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertEquals(MAPPER.valueToTree(List.of("client-value")), response.path("headers").path("x-unresolved"));
    }

    @ParameterizedTest
    @CsvSource({"HTTP_PROXY,true,true", "HTTP_PROXY,true,false", "HTTP_PROXY,false,true", "HTTP_PROXY,false,false",
            "HTTP,true,true", "HTTP,true,false", "HTTP,false,true", "HTTP,false,false"})
    void requestMappingsReadOriginalInputs(String type, boolean hasClaim, boolean copiesFirst) throws Exception {
        configureAuthorizer(0, hasClaim ? Map.of("userClaims", "verified-claims") : Map.of());
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("integration.request.header.X-User-Claims", "context.authorizer.userClaims");
        replacements.put("integration.request.header.X-Principal", "context.authorizer.principalId");
        replacements.put("integration.request.querystring.claims", "'mapped-query'");
        replacements.put("integration.request.path.principal", "context.authorizer.principalId");
        replacements.put("integration.request.path.proxy", "'mapped-path'");
        Map<String, String> copies = new LinkedHashMap<>();
        copies.put("integration.request.header.X-Original-Claims", "method.request.header.x-user-claims");
        copies.put("integration.request.header.X-Original-Query", "method.request.querystring.claims");
        copies.put("integration.request.header.X-Original-Path", "method.request.path.proxy");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.putAll(copiesFirst ? copies : replacements);
        parameters.putAll(copiesFirst ? replacements : copies);
        configureIntegration(type, "GET", parameters);
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "client-claims")
                    .get(endpoint + "orders/42?claims=client-query")
                    .then().statusCode(200).extract().asByteArray());
            JsonNode headers = response.path("headers");
            assertEquals("client-claims", headers.path("x-original-claims").path(0).asText());
            assertEquals("client-query", headers.path("x-original-query").path(0).asText());
            assertEquals("orders/42", headers.path("x-original-path").path(0).asText());
            assertEquals("/users/verified-user/mapped-path", response.path("path").asText());
            assertEquals("claims=mapped-query", response.path("query").asText());
            if (hasClaim) {
                assertEquals(MAPPER.valueToTree(List.of("verified-claims")), headers.path("x-user-claims"));
            } else {
                assertFalse(headers.has("x-user-claims"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void retainsContextOnRepeatedRequestsWithAuthorizerTtl(String type) throws Exception {
        configureAuthorizer(300);
        configureIntegration(type, "GET");
        deploy();
        String previousRequestId = null;
        for (int request = 0; request < 2; request++) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("X-User-Claims", "forged")
                    .get("/execute-api/" + apiId + "/test/orders/42")
                    .then().statusCode(200).extract().asByteArray());
            assertMappedContext(response);
            String requestId = response.path("headers").path("x-request-id").get(0).asText();
            assertNotEquals(previousRequestId, requestId);
            previousRequestId = requestId;
        }
        verify(lambdaService).invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void rejectedTokensNeverReachBackend(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        deploy();
        given().header("X-User-Claims", "forged")
                .get("/execute-api/" + apiId + "/test/orders/42").then().statusCode(401);
        given().header("X-User-Claims", "forged").header("Authorization", "Bearer forged")
                .get("/execute-api/" + apiId + "/test/orders/42").then().statusCode(403);
        assertEquals(0, backendRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void unauthenticatedOptionsHasRequestContextWithoutBearerClaims(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath + "OPTIONS").then().statusCode(201);
        configureIntegration(type, "OPTIONS");
        deploy();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer forged")
                .header("x-user-claims", "forged", "also-forged")
                .options("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertFalse(response.path("headers").has("x-user-claims"));
        assertFalse(response.path("headers").has("x-principal"));
        assertFalse(response.path("headers").has("x-missing"));
        assertFalse(response.path("headers").has("x-object"));
        assertEquals("OPTIONS", response.path("headers").path("x-context-method").path(0).asText());
        assertDoesNotThrow(() -> UUID.fromString(response.path("headers").path("x-request-id").path(0).asText()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsDocumentedRequestMetadataAndValidClientRequestId(String type) throws Exception {
        configureAuthorizer(0);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String field : List.of("requestId", "extendedRequestId", "domainName", "domainPrefix",
                "requestTime", "requestTimeEpoch", "identity.sourceIp")) {
            parameters.put("integration.request.header.X-" + field.replace('.', '-'), "context." + field);
        }
        parameters.put("integration.request.path.principal", "context.authorizer.principalId");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, "GET", parameters);
        deploy();
        String requestId = UUID.randomUUID().toString();
        long before = System.currentTimeMillis();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                .header("x-amzn-RequestId", requestId).header("X-Forwarded-For", "192.0.2.10")
                .get("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).header("x-amzn-RequestId", equalTo(requestId)).extract().asByteArray());
        JsonNode headers = response.path("headers");
        assertEquals(requestId, headers.path("x-requestid").path(0).asText());
        String extendedId = headers.path("x-extendedrequestid").path(0).asText();
        assertFalse(extendedId.isEmpty());
        assertNotEquals(requestId, extendedId);
        assertEquals("localhost", headers.path("x-domainname").path(0).asText());
        assertEquals("localhost", headers.path("x-domainprefix").path(0).asText());
        long requestTime = Long.parseLong(headers.path("x-requesttimeepoch").path(0).asText());
        assertTrue(requestTime >= before && requestTime <= System.currentTimeMillis());
        assertFalse(headers.path("x-requesttime").path(0).asText().isEmpty());
        assertEquals("127.0.0.1", headers.path("x-identity-sourceip").path(0).asText());
        if ("HTTP".equals(type)) {
            assertEquals(requestId, MAPPER.readTree(response.path("body").asText()).path("requestId").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad_host", "bad_host:8080"})
    void malformedHostDoesNotBreakMappingOrGatewayErrors(String host) throws Exception {
        configureAuthorizer(0);
        configureIntegration("HTTP_PROXY", "GET", Map.of(
                "integration.request.header.X-Domain", "context.domainName",
                "integration.request.path.principal", "context.authorizer.principalId",
                "integration.request.path.proxy", "method.request.path.proxy"));
        deploy();
        given().header("Host", host).header("Authorization", "Bearer allowed")
                .get("/execute-api/" + apiId + "/test/orders/42").then().statusCode(200)
                .body("headers.x-domain[0]", equalTo(apiId + ".execute-api.us-east-1.amazonaws.com"));
        given().contentType(ContentType.JSON).body(Map.of("responseParameters", Map.of(
                        "gatewayresponse.header.X-Domain", "context.domainName")))
                .put("/restapis/" + apiId + "/gatewayresponses/ACCESS_DENIED").then().statusCode(201);
        given().header("Host", host).header("Authorization", "Bearer forged")
                .get("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(403).header("X-Domain", equalTo(apiId + ".execute-api.us-east-1.amazonaws.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/execute-api/missing-api/test/orders", "/restapis/missing-api/test/_user_request_/orders"})
    void missingRestApiErrorsHaveRequestIdentifiers(String path) {
        String requestId = UUID.randomUUID().toString();
        Response response = given().header("x-amzn-RequestId", requestId)
                .get(path).then().statusCode(404).header("x-amzn-RequestId", equalTo(requestId)).extract().response();
        assertDoesNotThrow(() -> UUID.fromString(response.header("x-amz-apigw-id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsStageRepeatedValuesAndOriginalBodyFields(String type) throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath + "POST").then().statusCode(201);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("integration.request.header.X-Header-Copy", "method.request.multivalueheader.X-Source");
        parameters.put("integration.request.header.X-Scalar-Header", "method.request.header.X-Source");
        parameters.put("integration.request.querystring.copied", "method.request.multivaluequerystring.source");
        parameters.put("integration.request.querystring.scalar", "method.request.querystring.source");
        parameters.put("integration.request.header.X-Raw-Body", "method.request.body");
        parameters.put("integration.request.header.X-Body-Number", "method.request.body.items[0].number");
        parameters.put("integration.request.header.X-Body-Items", "method.request.body.items[*]");
        parameters.put("integration.request.header.X-Body-Numbers", "method.request.body.items[*].number");
        parameters.put("integration.request.header.X-Body-Missing", "method.request.body.missing");
        parameters.put("integration.request.header.X-Body-Invalid", "method.request.body.items[invalid]");
        parameters.put("integration.request.header.X-Body-Boolean", "method.request.body.enabled");
        parameters.put("integration.request.querystring.owner", "stageVariables.owner");
        parameters.put("integration.request.path.principal", "stageVariables.owner");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, "POST", parameters);
        deploy();
        given().contentType(ContentType.JSON).body(Map.of("patchOperations", List.of(Map.of(
                        "op", "add", "path", "/variables/owner", "value", "stage-owner"))))
                .patch("/restapis/" + apiId + "/stages/test").then().statusCode(200);
        String body = "{\"items\":[{\"number\":42},{\"number\":7}],\"enabled\":true}";
        JsonNode response = MAPPER.readTree(given().contentType(ContentType.JSON).body(body)
                .header("X-Source", "first", "second").queryParam("source", "one", "two")
                .post("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertEquals(MAPPER.valueToTree(List.of("first", "second")), response.path("headers").path("x-header-copy"));
        assertEquals(MAPPER.valueToTree(List.of("first")), response.path("headers").path("x-scalar-header"));
        assertEquals(body, response.path("headers").path("x-raw-body").path(0).asText());
        assertEquals("42", response.path("headers").path("x-body-number").path(0).asText());
        assertEquals("[{\"number\":42},{\"number\":7}]", response.path("headers").path("x-body-items").path(0).asText());
        assertEquals("[42,7]", response.path("headers").path("x-body-numbers").path(0).asText());
        assertFalse(response.path("headers").has("x-body-missing"));
        assertFalse(response.path("headers").has("x-body-invalid"));
        assertEquals("true", response.path("headers").path("x-body-boolean").path(0).asText());
        assertTrue(response.path("query").asText().contains("copied=one&copied=two"));
        assertTrue(List.of(response.path("query").asText().split("&")).contains("scalar=one"));
        assertTrue(response.path("query").asText().contains("owner=stage-owner"));
        assertEquals("/users/stage-owner/orders/42", response.path("path").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsArrayRootBodyFields(String type) throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath + "POST").then().statusCode(201);
        configureIntegration(type, "POST", Map.of(
                "integration.request.header.X-First-Price", "method.request.body[0].price",
                "integration.request.header.X-Prices", "method.request.body[*].price",
                "integration.request.path.principal", "'array'",
                "integration.request.path.proxy", "method.request.path.proxy"));
        deploy();
        JsonNode response = MAPPER.readTree(given().contentType(ContentType.JSON)
                .body("[{\"price\":42},{\"price\":7}]")
                .post("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertEquals("42", response.path("headers").path("x-first-price").path(0).asText());
        assertEquals("[42,7]", response.path("headers").path("x-prices").path(0).asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsCustomDomainAndPrefix(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET", Map.of("integration.request.header.X-Domain", "context.domainName",
                "integration.request.header.X-Prefix", "context.domainPrefix",
                "integration.request.path.principal", "context.authorizer.principalId",
                "integration.request.path.proxy", "method.request.path.proxy"));
        deploy();
        String domain = apiId + ".example.test";
        given().contentType(ContentType.JSON).body(Map.of("domainName", domain,
                        "certificateArn", "arn:aws:acm:us-east-1:000000000000:certificate/example"))
                .post("/domainnames").then().statusCode(201);
        try {
            given().contentType(ContentType.JSON).body(Map.of("basePath", "v1", "restApiId", apiId, "stage", "test"))
                    .post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);
            JsonNode response = MAPPER.readTree(given().header("Host", domain).header("Authorization", "Bearer allowed")
                    .get("/v1/orders/42").then().statusCode(200).extract().asByteArray());
            assertEquals(domain, response.path("headers").path("x-domain").path(0).asText());
            assertEquals(apiId, response.path("headers").path("x-prefix").path(0).asText());
        } finally {
            given().delete("/domainnames/" + domain).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void requestAuthorizerAndBackendShareRequestMetadata(String type) throws Exception {
        configureAuthorizer(0, Map.of("userClaims", "verified-claims"), "REQUEST");
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String field : List.of("requestId", "extendedRequestId", "requestTimeEpoch")) {
            parameters.put("integration.request.header.X-" + field, "context." + field);
        }
        parameters.put("integration.request.path.principal", "context.authorizer.principalId");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, "GET", parameters);
        deploy();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                .get("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        ArgumentCaptor<byte[]> invocation = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq(FUNCTION), invocation.capture(), eq(InvocationType.RequestResponse));
        JsonNode authorizerContext = MAPPER.readTree(invocation.getValue()).path("requestContext");
        for (String field : List.of("requestId", "extendedRequestId", "requestTimeEpoch")) {
            assertEquals(authorizerContext.path(field).asText(), response.path("headers").path("x-" + field.toLowerCase(Locale.ROOT)).path(0).asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsVerifiedIamIdentity(String type) throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "AWS_IAM"))
                .put(methodPath + "GET").then().statusCode(201);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String field : List.of("accessKey", "accountId", "caller", "user", "userArn")) {
            parameters.put("integration.request.header.X-" + field, "context.identity." + field);
        }
        parameters.put("integration.request.path.principal", "'iam'");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, "GET", parameters);
        deploy();
        String path = "/execute-api/" + apiId + "/test/orders/42";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(),
                "localhost:" + RestAssured.port, null, "test", "test", "us-east-1", Instant.now());
        JsonNode response = MAPPER.readTree(given().headers(signed).get(path)
                .then().statusCode(200).extract().asByteArray());
        assertEquals("test", response.path("headers").path("x-accesskey").path(0).asText());
        assertEquals("000000000000", response.path("headers").path("x-accountid").path(0).asText());
        assertFalse(response.path("headers").path("x-userarn").path(0).asText().isEmpty());
        assertEquals(response.path("headers").path("x-caller"), response.path("headers").path("x-user"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void mapsResolvedApiKey(String type) throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE", "apiKeyRequired", true))
                .put(methodPath + "GET").then().statusCode(201);
        configureIntegration(type, "GET", Map.of("integration.request.header.X-Key", "context.identity.apiKey",
                "integration.request.header.X-Key-Id", "context.identity.apiKeyId",
                "integration.request.path.principal", "'key'", "integration.request.path.proxy", "method.request.path.proxy"));
        deploy();
        JsonNode key = MAPPER.readTree(given().contentType(ContentType.JSON).body(Map.of("name", "mapping-key", "enabled", true))
                .post("/apikeys").then().statusCode(201).extract().asByteArray());
        String plan = given().contentType(ContentType.JSON).body(Map.of("name", "mapping-plan", "apiStages",
                        List.of(Map.of("apiId", apiId, "stage", "test"))))
                .post("/usageplans").then().statusCode(201).extract().path("id");
        try {
            given().contentType(ContentType.JSON).body(Map.of("keyId", key.path("id").asText(), "keyType", "API_KEY"))
                    .post("/usageplans/" + plan + "/keys").then().statusCode(201);
            JsonNode response = MAPPER.readTree(given().header("X-Api-Key", key.path("value").asText())
                    .get("/execute-api/" + apiId + "/test/orders/42")
                    .then().statusCode(200).extract().asByteArray());
            assertEquals(key.path("value").asText(), response.path("headers").path("x-key").path(0).asText());
            assertEquals(key.path("id").asText(), response.path("headers").path("x-key-id").path(0).asText());
        } finally {
            given().delete("/usageplans/" + plan).then().statusCode(202);
            given().delete("/apikeys/" + key.path("id").asText()).then().statusCode(202);
        }
    }

    private void assertMappedContext(JsonNode response) {
        JsonNode headers = response.path("headers");
        assertEquals(MAPPER.valueToTree(List.of("verified-claims")), headers.path("x-user-claims"));
        assertEquals(MAPPER.valueToTree(List.of("verified-user")), headers.path("x-principal"));
        assertEquals(MAPPER.valueToTree(List.of("123")), headers.path("x-number"));
        assertEquals(MAPPER.valueToTree(List.of("true")), headers.path("x-boolean"));
        assertEquals(MAPPER.valueToTree(List.of("test")), headers.path("x-context-stage"));
        assertFalse(headers.has("x-missing"));
        assertFalse(headers.has("x-object"));
        assertEquals(1, headers.path("x-request-id").size());
        assertDoesNotThrow(() -> UUID.fromString(headers.path("x-request-id").get(0).asText()));
    }

    private void configureAuthorizer(int ttl) throws Exception {
        configureAuthorizer(ttl, Map.of("userClaims", "verified-claims", "principalId", "forged-principal",
                "numberKey", 123, "booleanKey", true));
    }

    private void configureAuthorizer(int ttl, Map<String, Object> context) throws Exception {
        configureAuthorizer(ttl, context, "TOKEN");
    }

    private void configureAuthorizer(int ttl, Map<String, Object> context, String type) throws Exception {
        String authorizerId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "claims", "type", type, "authorizerUri", AUTHORIZER_URI,
                        "identitySource", "method.request.header.Authorization", "authorizerResultTtlInSeconds", ttl))
                .post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId))
                .put(methodPath + "GET").then().statusCode(201);
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    String effect = "REQUEST".equals(type) || "Bearer allowed".equals(event.path("authorizationToken").asText()) ? "Allow" : "Deny";
                    byte[] payload = MAPPER.writeValueAsBytes(Map.of("principalId", "verified-user",
                            "policyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                                    "Action", "execute-api:Invoke", "Effect", effect, "Resource", "*"))),
                            "context", context));
                    return new InvokeResult(200, null, payload, null, "authorizer-request");
                });
    }

    private void configureIntegration(String type, String method) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("integration.request.header.X-User-Claims", "context.authorizer.userClaims");
        parameters.put("integration.request.header.X-Principal", "context.authorizer.principalId");
        parameters.put("integration.request.header.X-Number", "context.authorizer.numberKey");
        parameters.put("integration.request.header.X-Boolean", "context.authorizer.booleanKey");
        parameters.put("integration.request.header.X-Missing", "context.authorizer.missing");
        // REST authorizer context rejects objects, so use the identity map to test non-scalar mappings.
        parameters.put("integration.request.header.X-Object", "context.identity");
        parameters.put("integration.request.header.X-Request-Id", "context.requestId");
        parameters.put("integration.request.header.X-Context-Stage", "context.stage");
        parameters.put("integration.request.header.X-Context-Path", "context.path");
        parameters.put("integration.request.header.X-Context-Method", "context.httpMethod");
        parameters.put("integration.request.header.X-Context-Api", "context.apiId");
        parameters.put("integration.request.header.X-Context-Resource", "context.resourceId");
        parameters.put("integration.request.header.X-From-Client", "method.request.header.X-Source");
        parameters.put("integration.request.header.X-Unresolved", "method.request.querystring.missing");
        parameters.put("integration.request.header.X-Static", "'static-value'");
        parameters.put("integration.request.querystring.claims", "context.authorizer.userClaims");
        parameters.put("integration.request.querystring.client", "method.request.querystring.client");
        parameters.put("integration.request.path.principal", "context.authorizer.principalId");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, method, parameters);
    }

    private void configureIntegration(String type, String method, Map<String, String> parameters) {
        // POST makes the HTTP integration's rendered request body observable at the echo backend.
        String integrationMethod = "HTTP".equals(type) && "GET".equals(method) ? "POST" : method;
        given().contentType(ContentType.JSON)
                .body(Map.of("type", type, "httpMethod", integrationMethod,
                        "uri", "http://127.0.0.1:" + backend.getAddress().getPort() + "/users/{principal}/{proxy}",
                        "requestParameters", parameters, "requestTemplates", Map.of("application/json",
                                "{\"requestId\":\"$context.requestId\"}")))
                .put(methodPath + method + "/integration").then().statusCode(201);
    }

    private void deploy() {
        given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }
}
