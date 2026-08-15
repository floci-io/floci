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
 * Integration tests for API key revocation on the data plane.
 *
 * <p>A {@code UsagePlanKey} stores its own copy of the API key value, and
 * {@code requestContext.identity.apiKey} is resolved by matching the {@code x-api-key} header against
 * those copies. Disabling a key through {@code UpdateApiKey} or removing it through
 * {@code DeleteApiKey} must therefore stop the value being recognised, otherwise a revoked credential
 * keeps identifying requests.
 *
 * <p>Floci does not implement the {@code apiKeyRequired} gate, so a revoked key produces a request
 * whose {@code identity.apiKey} is null rather than a 403. These tests assert the resolution
 * behaviour that exists today.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayApiKeyRevocationIntegrationTest {

    private static final String LAMBDA_BASE_PATH = "/2015-03-31/functions";
    private static final String ECHO_FUNCTION = "apigw-apikey-revocation-echo";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // State shared across ordered tests
    private static String apiId;
    private static String resourceId;
    private static String planId;
    private static String keyId;
    private static String keyValue;

    @Test @Order(1)
    void createEchoLambda() throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of("index.js", """
                exports.handler = async (event) => ({
                  statusCode: 200,
                  body: JSON.stringify({
                    apiKey: event.requestContext && event.requestContext.identity
                      ? event.requestContext.identity.apiKey
                      : null
                  })
                });
                """)));
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
                        """.formatted(ECHO_FUNCTION, ROLE_ARN, zipBase64))
                .when().post(LAMBDA_BASE_PATH)
                .then()
                .statusCode(201);
    }

    @Test @Order(2)
    void setupApiWithDeployedStage() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"apikey-revocation-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"echo\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        String uri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + ECHO_FUNCTION + "/invocations";
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                        """.formatted(uri))
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String depId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"apikey-revocation\"}")
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

    @Test @Order(3)
    void createKeyAndAttachToUsagePlan() {
        keyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"revocation-key\",\"enabled\":true,\"generateDistinctId\":true}")
                .when().post("/apikeys")
                .then().statusCode(201)
                .extract().path("id");

        keyValue = given()
                .when().get("/apikeys/" + keyId + "?includeValue=true")
                .then().statusCode(200)
                .extract().path("value");
        assertNotNull(keyValue);
        assertNotEquals(keyId, keyValue, "generateDistinctId=true should give the key a value distinct from its id");

        planId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"revocation-plan","apiStages":[{"apiId":"%s","stage":"prod"}]}
                        """.formatted(apiId))
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"keyId":"%s","keyType":"API_KEY"}
                        """.formatted(keyId))
                .when().post("/usageplans/" + planId + "/keys")
                .then().statusCode(201);
    }

    @Test @Order(4)
    void enabledKeyIsResolvedOnTheDataPlane() throws Exception {
        assertEquals(keyValue, invokeAndReadApiKey(keyValue),
                "an enabled key attached to a plan covering the stage must populate identity.apiKey");
    }

    @Test @Order(5)
    void disabledKeyIsNotResolvedOnTheDataPlane() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/enabled\",\"value\":\"false\"}]}")
                .when().patch("/apikeys/" + keyId)
                .then().statusCode(200)
                .body("enabled", org.hamcrest.Matchers.is(false));

        assertNull(invokeAndReadApiKey(keyValue),
                "a key disabled through UpdateApiKey must no longer populate identity.apiKey");
    }

    @Test @Order(6)
    void reEnabledKeyIsResolvedAgain() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/enabled\",\"value\":\"true\"}]}")
                .when().patch("/apikeys/" + keyId)
                .then().statusCode(200)
                .body("enabled", org.hamcrest.Matchers.is(true));

        assertEquals(keyValue, invokeAndReadApiKey(keyValue),
                "re-enabling the key must restore data-plane resolution");
    }

    @Test @Order(7)
    void deleteApiKeyDetachesItFromEveryUsagePlan() {
        given()
                .when().get("/usageplans/" + planId + "/keys")
                .then().statusCode(200)
                .body("item.find { it.id == '" + keyId + "' }", org.hamcrest.Matchers.notNullValue());

        given()
                .when().delete("/apikeys/" + keyId)
                .then().statusCode(202);

        given()
                .when().get("/usageplans/" + planId + "/keys")
                .then().statusCode(200)
                .body("item.find { it.id == '" + keyId + "' }", org.hamcrest.Matchers.nullValue());

        given()
                .when().get("/usageplans/" + planId + "/keys/" + keyId)
                .then().statusCode(404);
    }

    @Test @Order(8)
    void deletedKeyIsNotResolvedOnTheDataPlane() throws Exception {
        assertNull(invokeAndReadApiKey(keyValue),
                "a deleted key must no longer populate identity.apiKey");
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String invokeAndReadApiKey(String apiKeyHeader) throws Exception {
        String response = given()
                .header("x-api-key", apiKeyHeader)
                .when().get("/execute-api/" + apiId + "/prod/echo")
                .then().statusCode(200)
                .extract().asString();

        JsonNode body = OBJECT_MAPPER.readTree(response);
        JsonNode apiKey = body.path("apiKey");
        return apiKey.isNull() || apiKey.isMissingNode() ? null : apiKey.asText();
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
}
