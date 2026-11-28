package io.floci.aws.services.bedrock;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockOriginalEnforcedGuardrailIntegrationTest {

    /** ISO-8601 date-time: the {@code Timestamp} shape of the Bedrock model. */
    private static final String ISO_8601 = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    private static String guardrailId;
    private static String guardrailArn;
    private static String configId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Signs as the same access key in the given Region, so only the Region differs between calls. */
    private static RequestSpecification inRegion(String region) {
        return given()
            .contentType("application/json")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region
                + "/bedrock/aws4_request, SignedHeaders=host, Signature=abc");
    }

    @Test
    @Order(1)
    void createGuardrailWithAVersion() {
        guardrailId = inRegion("us-east-1")
            .body("""
                {"name": "enforced-port-1532", "blockedInputMessaging": "no", "blockedOutputsMessaging": "no"}
                """)
        .when()
            .post("/guardrails")
        .then()
            .statusCode(202)
            .extract().path("guardrailId");
        guardrailArn = inRegion("us-east-1").when().get("/guardrails/" + guardrailId).then().extract().path("guardrailArn");

        inRegion("us-east-1").body("{}").when().post("/guardrails/" + guardrailId).then().statusCode(202)
            .body("version", equalTo("1"));
    }

    @Test
    @Order(2)
    void putCreatesAConfiguration() {
        configId = inRegion("us-east-1")
            .body("""
                {"guardrailInferenceConfig": {
                  "guardrailIdentifier": "%s",
                  "guardrailVersion": "1",
                  "selectiveContentGuarding": {"system": "SELECTIVE", "messages": "COMPREHENSIVE"},
                  "modelEnforcement": {"includedModels": ["ALL"], "excludedModels": []}
                }}
                """.formatted(guardrailArn))
        .when()
            .put("/enforcedGuardrailsConfiguration")
        .then()
            .statusCode(200)
            .body("configId", matchesPattern("[a-z0-9]+"))
            .body("updatedAt", matchesPattern(ISO_8601))
            .body("updatedBy", endsWith(":root"))
            .extract().path("configId");
    }

    @Test
    @Order(3)
    void listReturnsTheConfigurationWithTheUnversionedGuardrailArn() {
        inRegion("us-east-1")
        .when()
            .get("/enforcedGuardrailsConfiguration")
        .then()
            .statusCode(200)
            .body("guardrailsConfig", hasSize(1))
            .body("guardrailsConfig[0].configId", equalTo(configId))
            .body("guardrailsConfig[0].guardrailArn", equalTo(guardrailArn))
            .body("guardrailsConfig[0].guardrailId", equalTo(guardrailId))
            .body("guardrailsConfig[0].guardrailVersion", equalTo("1"))
            .body("guardrailsConfig[0].selectiveContentGuarding.system", equalTo("SELECTIVE"))
            .body("guardrailsConfig[0].modelEnforcement.includedModels[0]", equalTo("ALL"))
            .body("guardrailsConfig[0].modelEnforcement.excludedModels", empty())
            .body("guardrailsConfig[0].createdAt", matchesPattern(ISO_8601))
            .body("guardrailsConfig[0].createdBy", notNullValue())
            .body("guardrailsConfig[0].updatedAt", matchesPattern(ISO_8601))
            .body("guardrailsConfig[0].owner", equalTo("ACCOUNT"))
            .body("nextToken", nullValue());
    }

    @Test
    @Order(4)
    void anotherRegionListsNoConfiguration() {
        inRegion("eu-west-1")
        .when()
            .get("/enforcedGuardrailsConfiguration")
        .then()
            .statusCode(200)
            .body("guardrailsConfig", empty());
    }

    @Test
    @Order(5)
    void putRejectsTheDraftVersion() {
        inRegion("us-east-1")
            .body("""
                {"guardrailInferenceConfig": {"guardrailIdentifier": "%s", "guardrailVersion": "DRAFT"}}
                """.formatted(guardrailId))
        .when()
            .put("/enforcedGuardrailsConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void deleteRemovesTheConfigurationOnce() {
        inRegion("us-east-1").when().delete("/enforcedGuardrailsConfiguration/" + configId).then().statusCode(200);

        inRegion("us-east-1").when().get("/enforcedGuardrailsConfiguration").then()
            .body("guardrailsConfig", empty());
        inRegion("us-east-1").when().delete("/enforcedGuardrailsConfiguration/" + configId).then()
            .statusCode(404);
    }
}
