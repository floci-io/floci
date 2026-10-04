package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RestLambdaAuthorizerIntegrationTest {
    private static final String FUNCTION = "rest-contract-authorizer";
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + FUNCTION + "/invocations";

    @InjectMock
    LambdaService lambdaService;

    private String apiId;
    private String authorizerId;

    @BeforeEach
    void createApi() {
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "rest-authorizer-contract"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        for (String path : List.of("allowed", "other")) {
            String resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", path))
                    .post("/restapis/" + apiId + "/resources/" + rootId)
                    .then().statusCode(201).extract().path("id");

        }
    }

    @AfterEach
    void deleteApi() {
        given().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "action", "laterDeny", "firstUnmatched"})
    void evaluatesEveryStatementAgainstRequestedActionAndResource(String scenario) throws Exception {
        configure("TOKEN", 0, "method.request.header.Authorization");
        Map<String, String> allow = statement("Allow", "execute-api:Invoke", "*");
        List<Map<String, String>> statements = switch (scenario) {
            case "resource" -> List.of(statement("Allow", "execute-api:Invoke", methodArn("other")));
            case "action" -> List.of(statement("Allow", "execute-api:ManageConnections", "*"));
            case "laterDeny" -> List.of(allow, statement("Deny", "execute-api:Invoke", methodArn("allowed")));
            default -> List.of(statement("Deny", "execute-api:Invoke", methodArn("other")), allow);
        };
        respond(Map.of("principalId", "verified", "policyDocument", policy(statements)));
        given().header("Authorization", "token").get(execute("allowed"))
                .then().statusCode("firstUnmatched".equals(scenario) ? 200 : 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"principal", "object", "array", "null", "policy"})
    void invalidOutputFailsBeforeIntegration(String scenario) throws Exception {
        configure("TOKEN", 0, "method.request.header.Authorization");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("principalId", "verified");
        output.put("policyDocument", policy(List.of(statement("Allow", "execute-api:Invoke", "*"))));
        switch (scenario) {
            case "principal" -> output.remove("principalId");
            case "object" -> output.put("context", Map.of("claims", Map.of("nested", "value")));
            case "array" -> output.put("context", Map.of("claims", List.of("value")));
            case "null" -> output.put("context", Collections.singletonMap("claims", null));
            default -> output.put("policyDocument", Map.of("Statement", List.of(Map.of("Effect", "Allow"))));
        }
        respond(output);
        given().header("Authorization", "token").get(execute("allowed")).then().statusCode(500);
    }

    @ParameterizedTest
    @CsvSource({"default,missing", "300,missing", "300,empty", "300,blank"})
    void cachedRequestAuthorizerRequiresIdentitySourceAtCreation(String ttl, String source) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "invalid-request-authorizer");
        request.put("type", "REQUEST");
        request.put("authorizerUri", AUTHORIZER_URI);
        if (!"default".equals(ttl)) {
            request.put("authorizerResultTtlInSeconds", Integer.parseInt(ttl));
        }
        if (!"missing".equals(source)) {
            request.put("identitySource", "blank".equals(source) ? "   " : "");
        }
        given().contentType(ContentType.JSON).body(request)
                .post("/restapis/" + apiId + "/authorizers").then().statusCode(400);
        given().get("/restapis/" + apiId + "/authorizers")
                .then().statusCode(200).body("item.size()", equalTo(0));
    }

    @Test
    void uncachedRequestAuthorizerCanOmitIdentitySource() throws Exception {
        configure("REQUEST", 0, null);
        allowAll();
        given().get(execute("allowed")).then().statusCode(200);
        verifyInvocation(1);
    }

    @ParameterizedTest
    @CsvSource({"300,method.request.header.Authorization,/identitySource,   ",
            "0,,/authorizerResultTtlInSeconds,300"})
    void invalidRequestAuthorizerUpdateLeavesConfigurationUnchanged(int ttl, String source, String path, String value) {
        configure("REQUEST", ttl, source);
        given().contentType(ContentType.JSON).body(Map.of("patchOperations", List.of(
                        Map.of("op", "replace", "path", "/name", "value", "changed"),
                        Map.of("op", "replace", "path", path, "value", value != null ? value : ""))))
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(400);
        given().get("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(200)
                .body("name", equalTo("contract"), "authorizerResultTtlInSeconds", equalTo(ttl));
    }

    @Test
    void requestAuthorizerCanEnableCachingAndIdentitySourceInOnePatch() {
        configure("REQUEST", 0, null);
        given().contentType(ContentType.JSON).body(Map.of("patchOperations", List.of(
                        Map.of("op", "replace", "path", "/authorizerResultTtlInSeconds", "value", "300"),
                        Map.of("op", "replace", "path", "/identitySource", "value", "method.request.header.Authorization"))))
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(200)
                .body("authorizerResultTtlInSeconds", equalTo(300),
                        "identitySource", equalTo("method.request.header.Authorization"));
    }

    @Test
    void cachedHyphenatedContextReachesLambdaProxy() throws Exception {
        configure("TOKEN", 300, "method.request.header.Authorization");
        respond(Map.of("principalId", "verified", "context", Map.of("tenant-id", "tenant-one", "plan.level", 2),
                "policyDocument", policy(List.of(statement("Allow", "execute-api:Invoke", "*")))));
        String resourceId = given().get("/restapis/" + apiId + "/resources").then().extract()
                .path("item.find { it.path == '/allowed' }.id");
        given().contentType(ContentType.JSON).body(Map.of("type", "AWS_PROXY", "httpMethod", "POST",
                        "uri", AUTHORIZER_URI.replace(FUNCTION, "context-proxy")))
                .put(methodPath(resourceId) + "/integration").then().statusCode(201);
        when(lambdaService.invoke(eq("us-east-1"), eq("context-proxy"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    ObjectMapper mapper = new ObjectMapper();
                    String context = mapper.readTree((byte[]) invocation.getArgument(2))
                            .path("requestContext").path("authorizer").toString();
                    return new InvokeResult(200, null,
                            mapper.writeValueAsBytes(Map.of("statusCode", 200, "body", context)), null, "proxy-request");
                });
        deploy("test");
        for (int request = 0; request < 2; request++) {
            given().header("Authorization", "token").get(execute("allowed"))
                    .then().statusCode(200).body("'tenant-id'", equalTo("tenant-one"),
                            "'plan.level'", equalTo("2"), "principalId", equalTo("verified"));
        }
        verifyInvocation(1);
    }

    @Test
    void missingTokenIsUnauthorizedWithoutInvokingLambda() throws Exception {
        configure("TOKEN", 0, "method.request.header.Authorization");
        allowAll();
        given().get(execute("allowed")).then().statusCode(401);
        verifyInvocation(0);
    }

    @Test
    void cachedPolicyAndContextAreReusedButPolicyStillGatesEachRoute() throws Exception {
        configure("TOKEN", 300, "method.request.header.Authorization");
        respond(Map.of("principalId", "verified", "context", Map.of("userClaims", "trusted"),
                "policyDocument", policy(List.of(statement("Allow", "execute-api:Invoke", methodArn("allowed"))))));
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "token").header("X-User-Claims", "forged")
                    .get(execute("allowed")).then().statusCode(200)
                    .body("principal", equalTo("verified"), "claims", equalTo("trusted"));
        }
        given().header("Authorization", "token").get(execute("other")).then().statusCode(403);
        verifyInvocation(1);
    }

    @Test
    void zeroTtlInvokesForEachRequest() throws Exception {
        configure("TOKEN", 0, "method.request.header.Authorization");
        allowAll();
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "token").get(execute("allowed")).then().statusCode(200);
        }
        verifyInvocation(2);
    }

    @Test
    void flushingStageAndRedeployingInvalidateCachedResult() throws Exception {
        configure("TOKEN", 300, "method.request.header.Authorization");
        allowAll();
        given().header("Authorization", "token").get(execute("allowed")).then().statusCode(200);
        given().delete("/restapis/" + apiId + "/stages/test/cache/authorizers").then().statusCode(202).body(equalTo(""));
        given().header("Authorization", "token").get(execute("allowed")).then().statusCode(200);
        deploy("test");
        given().header("Authorization", "token").get(execute("allowed")).then().statusCode(200);
        verifyInvocation(3);
        given().delete("/restapis/" + apiId + "/stages/missing/cache/authorizers").then().statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "REQUEST"})
    void flushPreventsInFlightInvocationFromRestoringStaleContext(String type) throws Exception {
        configure(type, 300, "method.request.header.Authorization");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    boolean first = invocations.incrementAndGet() == 1;
                    if (first) {
                        started.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS), "Authorizer invocation was not released");
                    }
                    byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of("principalId", "verified",
                            "policyDocument", policy(List.of(statement("Allow", "execute-api:Invoke", "*"))),
                            "context", Map.of("userClaims", first ? "stale" : "fresh")));
                    return new InvokeResult(200, null, payload, null, "lambda-request");
                });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> request = executor.submit(() -> given().header("Authorization", "token")
                    .get(execute("allowed")).then().statusCode(200).body("claims", equalTo("stale")));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS), "Authorizer invocation did not start");
                given().delete("/restapis/" + apiId + "/stages/test/cache/authorizers").then().statusCode(202);
            } finally {
                release.countDown();
            }
            request.get(10, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "token").get(execute("allowed"))
                    .then().statusCode(200).body("claims", equalTo("fresh"));
        }
        verifyInvocation(2);
    }

    @Test
    void changedAuthorizerConfigurationInvalidatesCachedResult() throws Exception {
        configure("TOKEN", 300, "method.request.header.Authorization");
        allowAll();
        given().header("Authorization", "token").get(execute("allowed")).then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("patchOperations", List.of(Map.of(
                        "op", "replace", "path", "/identitySource", "value", "method.request.header.New-Token"))))
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(200);
        given().header("New-Token", "token").get(execute("allowed")).then().statusCode(200);
        verifyInvocation(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 300})
    void requestIdentitySourcesAreRequiredOnlyWhenCaching(int ttl) throws Exception {
        configure("REQUEST", ttl, "method.request.header.Authorization,method.request.querystring.tenant");
        allowAll();
        given().header("Authorization", "token").get(execute("allowed"))
                .then().statusCode(ttl == 0 ? 200 : 401);
        verifyInvocation(ttl == 0 ? 1 : 0);
    }

    @Test
    void requestCacheUsesAllIdentitySources() throws Exception {
        configure("REQUEST", 300, "method.request.header.Authorization,method.request.querystring.tenant");
        allowAll();
        for (String tenant : List.of("one", "one", "two")) {
            given().header("Authorization", "token").queryParam("tenant", tenant)
                    .get(execute("allowed")).then().statusCode(200);
        }
        verifyInvocation(2);
    }

    @Test
    void requestContextIdentitySourcesSeparateRoutePolicies() throws Exception {
        configure("REQUEST", 300, "method.request.header.Authorization,context.resourcePath");
        allowAll();
        for (String path : List.of("allowed", "allowed", "other")) {
            given().header("Authorization", "token").get(execute(path)).then().statusCode(200);
        }
        verifyInvocation(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unauthorized", "failure"})
    void lambdaErrorsDistinguishUnauthorizedFromFailure(String message) throws Exception {
        configure("TOKEN", 300, "method.request.header.Authorization");
        byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of("errorMessage", message));
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Unhandled", payload, null, "lambda-error"));
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", "token").get(execute("allowed"))
                    .then().statusCode("Unauthorized".equals(message) ? 401 : 500);
        }
        verifyInvocation(2);
    }

    private void configure(String type, int ttl, String identitySource) {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "contract", "type", type,
                "authorizerUri", AUTHORIZER_URI, "authorizerResultTtlInSeconds", ttl));
        if (identitySource != null) {
            request.put("identitySource", identitySource);
        }
        authorizerId = given().contentType(ContentType.JSON).body(request)
                .post("/restapis/" + apiId + "/authorizers").then().statusCode(201).extract().path("id");
        List<String> resources = given().get("/restapis/" + apiId + "/resources")
                .then().extract().path("item.findAll { it.path != '/' }.id");
        for (String resourceId : resources) {
            given().contentType(ContentType.JSON).body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId))
                    .put(methodPath(resourceId)).then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of("type", "MOCK",
                            "requestTemplates", Map.of("application/json", "{\"statusCode\":200}")))
                    .put(methodPath(resourceId) + "/integration").then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of())
                    .put(methodPath(resourceId) + "/responses/200").then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of("responseTemplates", Map.of("application/json",
                            "{\"principal\":\"$context.authorizer.principalId\","
                                    + "\"claims\":\"$context.authorizer.userClaims\"}")))
                    .put(methodPath(resourceId) + "/integration/responses/200").then().statusCode(201);
        }
        deploy("test");
    }

    private void deploy(String stage) {
        given().contentType(ContentType.JSON).body(Map.of("stageName", stage))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }

    private void allowAll() throws Exception {
        respond(Map.of("principalId", "verified", "policyDocument",
                policy(List.of(statement("Allow", "execute-api:Invoke", "*")))));
    }

    private void respond(Map<String, Object> output) throws Exception {
        byte[] payload = new ObjectMapper().writeValueAsBytes(output);
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, payload, null, "lambda-request"));
    }

    private void verifyInvocation(int count) {
        if (count == 0) {
            verify(lambdaService, never()).invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
        } else {
            verify(lambdaService, times(count)).invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
        }
    }

    private static Map<String, String> statement(String effect, String action, String resource) {
        return Map.of("Effect", effect, "Action", action, "Resource", resource);
    }

    private static Map<String, Object> policy(List<Map<String, String>> statements) {
        return Map.of("Version", "2012-10-17", "Statement", statements);
    }

    private String methodPath(String resourceId) {
        return "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
    }

    private String methodArn(String path) {
        return "arn:aws:execute-api:us-east-1:000000000000:" + apiId + "/test/GET/" + path;
    }

    private String execute(String path) {
        return "/execute-api/" + apiId + "/test/" + path;
    }
}
