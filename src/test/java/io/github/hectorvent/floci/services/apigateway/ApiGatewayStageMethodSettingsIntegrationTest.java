package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UpdateStage method-settings patch operations (e.g. <code>/*&#47;*&#47;metrics/enabled</code>,
 * <code>/*&#47;*&#47;logging/loglevel</code>, <code>/*&#47;*&#47;throttling/burstLimit</code>)
 * must persist on the stage and round-trip through GetStage as the
 * <code>methodSettings</code> map. This is what the Terraform AWS provider's
 * <code>aws_api_gateway_method_settings</code> resource requires.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayStageMethodSettingsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STAGE_NAME = "test";
    private static final String WILDCARD_KEY = "*/*";

    private static String apiId;
    private static String deploymentId;

    @Test
    @Order(0)
    void setup_createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"methodSettings-test-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test
    @Order(1)
    void setup_createDeploymentAndStage() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"initial\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE_NAME + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(2)
    void updateStage_methodSettingsPatchOperations_persistOnGetStage() throws Exception {
        String patchBody = """
                {
                  "patchOperations": [
                    {"op":"replace","path":"/*/*/metrics/enabled","value":"true"},
                    {"op":"replace","path":"/*/*/logging/loglevel","value":"ERROR"},
                    {"op":"replace","path":"/*/*/logging/dataTrace","value":"true"},
                    {"op":"replace","path":"/*/*/throttling/burstLimit","value":"5000"},
                    {"op":"replace","path":"/*/*/throttling/rateLimit","value":"10000.0"}
                  ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(patchBody)
                .when().patch("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200);

        String response = given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200)
                .extract().asString();

        JsonNode stage = MAPPER.readTree(response);
        JsonNode methodSettings = stage.path("methodSettings");
        assertTrue(methodSettings.isObject() && methodSettings.has(WILDCARD_KEY),
                "GetStage response must include methodSettings[\"" + WILDCARD_KEY + "\"], got: " + response);

        JsonNode wildcard = methodSettings.path(WILDCARD_KEY);
        assertEquals(true, wildcard.path("metricsEnabled").asBoolean(),
                "metricsEnabled must persist as true");
        assertEquals("ERROR", wildcard.path("loggingLevel").asText(),
                "loggingLevel must persist as ERROR");
        assertEquals(true, wildcard.path("dataTraceEnabled").asBoolean(),
                "dataTraceEnabled must persist as true");
        assertEquals(5000, wildcard.path("throttlingBurstLimit").asInt(),
                "throttlingBurstLimit must persist as 5000");
        assertEquals(10000.0, wildcard.path("throttlingRateLimit").asDouble(), 0.0001,
                "throttlingRateLimit must persist as 10000.0");
    }

    @Test
    @Order(3)
    void updateStage_methodSettingsCachingPatchOperations_persist() throws Exception {
        String patchBody = """
                {
                  "patchOperations": [
                    {"op":"replace","path":"/*/*/caching/enabled","value":"true"},
                    {"op":"replace","path":"/*/*/caching/ttlInSeconds","value":"300"},
                    {"op":"replace","path":"/*/*/caching/dataEncrypted","value":"false"}
                  ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(patchBody)
                .when().patch("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200);

        String response = given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200)
                .extract().asString();

        JsonNode wildcard = MAPPER.readTree(response).path("methodSettings").path(WILDCARD_KEY);
        assertEquals(true, wildcard.path("cachingEnabled").asBoolean(),
                "cachingEnabled must persist as true");
        assertEquals(300, wildcard.path("cacheTtlInSeconds").asInt(),
                "cacheTtlInSeconds must persist as 300");
        assertEquals(false, wildcard.path("cacheDataEncrypted").asBoolean(),
                "cacheDataEncrypted must persist as false");
    }

    @Test
    @Order(4)
    void updateStage_perMethodPatchOperations_keyedByResourcePathAndMethod() throws Exception {
        String patchBody = """
                {
                  "patchOperations": [
                    {"op":"replace","path":"/pets/GET/metrics/enabled","value":"true"},
                    {"op":"replace","path":"/pets/GET/throttling/burstLimit","value":"42"}
                  ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(patchBody)
                .when().patch("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200);

        String response = given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE_NAME)
                .then().statusCode(200)
                .extract().asString();

        JsonNode petsGet = MAPPER.readTree(response).path("methodSettings").path("pets/GET");
        assertTrue(petsGet.isObject(),
                "GetStage response must include methodSettings[\"pets/GET\"], got: " + response);
        assertEquals(true, petsGet.path("metricsEnabled").asBoolean());
        assertEquals(42, petsGet.path("throttlingBurstLimit").asInt());
    }
}
