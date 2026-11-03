package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A REST API's {@code apiKeySource}: kept on create, on a {@code /apiKeySource} patch and from an
 * imported {@code x-amazon-apigateway-api-key-source}, and, set to {@code AUTHORIZER}, taking a
 * request's API key from the Lambda authorizer's {@code usageIdentifierKey} instead of the
 * {@code x-api-key} header.
 *
 * @see <a href="https://docs.aws.amazon.com/apigateway/latest/api/API_RestApi.html">RestApi</a>
 */
@QuarkusTest
class ApiGatewayApiKeySourceIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String AUTHORIZER = "api-key-source-authorizer";
    private static final String BACKEND = "api-key-source-backend";

    @InjectMock
    LambdaService lambdaService;

    private final ObjectMapper mapper = new ObjectMapper();
    private String apiId;
    private String keyId;
    private String planId;

    @BeforeEach
    void reset() {
        apiId = null;
        keyId = null;
        planId = null;
    }

    /**
     * Deletes whatever the test got as far as creating, even when an assertion failed first: an API
     * key and a usage plan outlive the REST API they were set up for. Deleting the key also
     * detaches it from the plan.
     */
    @AfterEach
    void deleteFixtures() {
        if (keyId != null) {
            given().delete("/apikeys/" + keyId).then().statusCode(202);
        }
        if (planId != null) {
            given().delete("/usageplans/" + planId).then().statusCode(202);
        }
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void theSourceIsKeptOnCreateAndPatchAndDefaultsToHeader() {
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "key-source", "apiKeySource", "AUTHORIZER"))
                .post("/restapis").then().statusCode(201)
                .body("apiKeySource", equalTo("AUTHORIZER"))
                .extract().path("id");
        given().get("/restapis/" + apiId).then().statusCode(200).body("apiKeySource", equalTo("AUTHORIZER"));

        patch("HEADER").statusCode(200).body("apiKeySource", equalTo("HEADER"));
        given().get("/restapis/" + apiId).then().body("apiKeySource", equalTo("HEADER"));
        patch("AUTHORIZER").statusCode(200).body("apiKeySource", equalTo("AUTHORIZER"));

        patch("QUERY").statusCode(400).body("message", equalTo("API key source must be HEADER or AUTHORIZER."));
        given().get("/restapis/" + apiId).then().body("apiKeySource", equalTo("AUTHORIZER"));
        // A refused source leaves the operations before it in the same request unapplied too.
        given().contentType(ContentType.JSON)
                .body(Map.of("patchOperations", List.of(
                        Map.of("op", "replace", "path", "/name", "value", "renamed"),
                        Map.of("op", "replace", "path", "/apiKeySource", "value", "QUERY"))))
                .patch("/restapis/" + apiId).then().statusCode(400);
        given().get("/restapis/" + apiId).then()
                .body("name", equalTo("key-source"), "apiKeySource", equalTo("AUTHORIZER"));

        given().contentType(ContentType.JSON).body(Map.of("name", "key-source-bad", "apiKeySource", "header"))
                .post("/restapis").then().statusCode(400);

        String defaulted = given().contentType(ContentType.JSON).body(Map.of("name", "key-source-default"))
                .post("/restapis").then().statusCode(201)
                .body("apiKeySource", equalTo("HEADER"))
                .extract().path("id");
        given().delete("/restapis/" + defaulted).then().statusCode(202);
    }

    @Test
    void anImportedDefinitionSetsTheSource() {
        String definition = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "imported-key-source", "version": "1"},
                  "x-amazon-apigateway-api-key-source": "AUTHORIZER",
                  "paths": {}
                }
                """;
        apiId = given().contentType(ContentType.JSON).body(definition)
                .post("/restapis?mode=import").then().statusCode(201)
                .body("apiKeySource", equalTo("AUTHORIZER"))
                .extract().path("id");
        given().get("/restapis/" + apiId).then().body("apiKeySource", equalTo("AUTHORIZER"));
    }

    @Test
    void withAuthorizerAsSourceTheKeyComesFromTheAuthorizerNotTheHeader() throws Exception {
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "key-from-authorizer", "apiKeySource", "AUTHORIZER"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources").then().extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", "orders"))
                .post("/restapis/" + apiId + "/resources/" + rootId).then().statusCode(201).extract().path("id");
        String authorizerId = given().contentType(ContentType.JSON).body(Map.of("name", "tenant", "type", "TOKEN",
                        "authorizerUri", invocationUri(AUTHORIZER), "authorizerResultTtlInSeconds", 0,
                        "identitySource", "method.request.header.Authorization"))
                .post("/restapis/" + apiId + "/authorizers").then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON)
                .body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId, "apiKeyRequired", true))
                .put(method).then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("type", "AWS_PROXY", "httpMethod", "POST", "uri", invocationUri(BACKEND)))
                .put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON).body(Map.of("stageName", "prod"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);

        keyId = given().contentType(ContentType.JSON).body(Map.of("name", "tenant-key", "enabled", true))
                .post("/apikeys").then().statusCode(201).extract().path("id");
        String keyValue = given().get("/apikeys/" + keyId + "?includeValue=true").then().extract().path("value");
        planId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "tenant-plan", "apiStages", List.of(Map.of("apiId", apiId, "stage", "prod"))))
                .post("/usageplans").then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON).body(Map.of("keyId", keyId, "keyType", "API_KEY"))
                .post("/usageplans/" + planId + "/keys").then().statusCode(201);

        // The authorizer returns the bearer token as the key to meter the request with.
        when(lambdaService.invokeTarget(eq(REGION), eq(functionArn(AUTHORIZER)), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    String token = mapper.readTree((byte[]) invocation.getArgument(2)).path("authorizationToken").asText();
                    Map<String, Object> output = new HashMap<>();
                    output.put("principalId", "tenant");
                    output.put("policyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(
                            Map.of("Effect", "Allow", "Action", "execute-api:Invoke", "Resource", "*"))));
                    if (!"none".equals(token)) {
                        output.put("usageIdentifierKey", token);
                    }
                    return new InvokeResult(200, null, mapper.writeValueAsBytes(output), null, "authorizer-request");
                });
        when(lambdaService.invokeTarget(eq(REGION), eq(functionArn(BACKEND)), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    String apiKeyId = mapper.readTree((byte[]) invocation.getArgument(2))
                            .path("requestContext").path("identity").path("apiKeyId").asText();
                    return new InvokeResult(200, null, mapper.writeValueAsBytes(Map.of("statusCode", 200,
                            "body", mapper.writeValueAsString(Map.of("apiKeyId", apiKeyId)))), null, "backend-request");
                });

        String orders = "/execute-api/" + apiId + "/prod/orders";
        given().header("Authorization", keyValue).get(orders).then().statusCode(200).body("apiKeyId", equalTo(keyId));
        // The header is not read: a valid key in x-api-key does not stand in for the authorizer's.
        given().header("Authorization", "none").header("x-api-key", keyValue).get(orders).then().statusCode(403)
                .header("x-amzn-ErrorType", equalTo("ForbiddenException"));
        given().header("Authorization", "not-a-key").get(orders).then().statusCode(403);
    }

    private ValidatableResponse patch(String source) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("patchOperations", List.of(Map.of("op", "replace", "path", "/apiKeySource", "value", source))))
                .patch("/restapis/" + apiId).then();
    }

    private static String functionArn(String function) {
        return "arn:aws:lambda:" + REGION + ":000000000000:function:" + function;
    }

    private static String invocationUri(String function) {
        return "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/" + functionArn(function)
                + "/invocations";
    }
}
