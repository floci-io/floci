package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the SES V2 per-configuration-set SuppressionOptions
 * endpoint: PUT /v2/email/configuration-sets/{name}/suppression-options and the
 * matching GET /v2/email/configuration-sets/{name} response shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetSuppressionOptionsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String REGION = "us-east-1";
    private static final String CS = "cs-suppression-options";

    @Inject
    SesService sesService;

    @Test
    @Order(1)
    void createConfigurationSet() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void getConfigurationSetBeforePut_doesNotIncludeSuppressionOptions() {
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("ConfigurationSetName", equalTo(CS))
                .body("SuppressionOptions", nullValue());
    }

    @Test
    @Order(3)
    void putSuppressionOptions_bounceOnly() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":[\"BOUNCE\"]}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void getConfigurationSet_afterPut_reflectsSuppressionOptions() {
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SuppressionOptions.SuppressedReasons", contains("BOUNCE"))
                .body("SuppressionOptions.SuppressedReasons", hasSize(1));
    }

    @Test
    @Order(5)
    void putSuppressionOptions_emptyList_explicitlyDisablesFiltering() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":[]}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SuppressionOptions.SuppressedReasons", empty());
    }

    @Test
    @Order(6)
    void putSuppressionOptions_bothReasons() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":[\"BOUNCE\",\"COMPLAINT\"]}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SuppressionOptions.SuppressedReasons", contains("BOUNCE", "COMPLAINT"));
    }

    @Test
    @Order(7)
    void putSuppressionOptions_invalidReason_rejected() {
        // Message verified against real AWS V2 SES on 2026-06-03:
        //   "Reason INVALID is invalid, must be one of [BOUNCE, COMPLAINT]."
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":[\"BOUNCE\",\"INVALID\"]}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.equalTo("BadRequestException"))
                .body("message", org.hamcrest.Matchers.equalTo(
                        "Reason INVALID is invalid, must be one of [BOUNCE, COMPLAINT]."));
    }

    @Test
    @Order(8)
    void putSuppressionOptions_nonArrayReasons_rejected() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":\"BOUNCE\"}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(400);
    }

    @Test
    @Order(9)
    void putSuppressionOptions_unknownConfigSet_returns404() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"SuppressedReasons\":[\"BOUNCE\"]}")
        .when()
                .put("/v2/email/configuration-sets/does-not-exist/suppression-options")
        .then()
                .statusCode(404);
    }

    @Test
    @Order(10)
    void putSuppressionOptions_omittedSuppressedReasons_treatedAsEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/suppression-options")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SuppressionOptions.SuppressedReasons", empty());
    }

    // ─── getEffectiveSuppressedReasons helper (forward-looking integration with #1109) ───

    @Test
    @Order(20)
    void effectiveReasons_csWithoutOverride_fallsBackToAccountLevel() {
        String csNoOverride = "cs-suppression-no-override";
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\":\"" + csNoOverride + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        List<String> effective = sesService.getEffectiveSuppressedReasons(csNoOverride, REGION);
        // Account defaults to [BOUNCE, COMPLAINT]; matches account state when no per-CS override.
        assertEquals(sesService.getAccountSuppressionAttributes(REGION).getSuppressedReasons(), effective);
    }

    @Test
    @Order(21)
    void effectiveReasons_csWithBounceOverride_returnsBounceOnly() {
        String csBounceOnly = "cs-suppression-bounce-only";
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\":\"" + csBounceOnly + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        sesService.putConfigurationSetSuppressionOptions(csBounceOnly, List.of("BOUNCE"), REGION);

        List<String> effective = sesService.getEffectiveSuppressedReasons(csBounceOnly, REGION);
        assertEquals(List.of("BOUNCE"), effective);
    }

    @Test
    @Order(22)
    void effectiveReasons_csWithEmptyOverride_explicitlyDisablesFiltering() {
        String csEmpty = "cs-suppression-empty";
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\":\"" + csEmpty + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        sesService.putConfigurationSetSuppressionOptions(csEmpty, List.of(), REGION);

        List<String> effective = sesService.getEffectiveSuppressedReasons(csEmpty, REGION);
        assertTrue(effective.isEmpty(),
                "An empty SuppressedReasons override must defeat the account-level reasons, "
                        + "matching the AWS V2 contract: \"If you specify an empty list, "
                        + "Amazon SES doesn't apply any suppression filtering ...\"");
    }

    @Test
    @Order(23)
    void effectiveReasons_nullConfigSetName_returnsAccountLevel() {
        List<String> effective = sesService.getEffectiveSuppressedReasons(null, REGION);
        assertEquals(sesService.getAccountSuppressionAttributes(REGION).getSuppressedReasons(), effective);
    }

    @Test
    @Order(24)
    void effectiveReasons_blankConfigSetName_returnsAccountLevel() {
        List<String> effective = sesService.getEffectiveSuppressedReasons("   ", REGION);
        assertEquals(sesService.getAccountSuppressionAttributes(REGION).getSuppressedReasons(), effective);
    }

    @Test
    @Order(25)
    void effectiveReasons_nonExistentConfigSet_throws() {
        assertThrows(AwsException.class,
                () -> sesService.getEffectiveSuppressedReasons("never-created", REGION));
    }
}
