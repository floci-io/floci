package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for the SES V2 account VDM attributes: {@code PUT /v2/email/account/vdm}
 * (PutAccountVdmAttributes) and the {@code GET /v2/email/account} (GetAccount) round-trip. VDM is
 * account/region-scoped, so this uses an isolated region and leaves it DISABLED at the end. Shapes
 * and defaults are verified against real AWS (VDM is opt-in, so it defaults to DISABLED).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesAccountVdmV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-central-1/ses/aws4_request";

    @Test
    @Order(0)
    void getAccount_vdmDefaultsToDisabled() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("DISABLED"))
                .body("VdmAttributes.DashboardAttributes.EngagementMetrics", equalTo("DISABLED"))
                .body("VdmAttributes.GuardianAttributes.OptimizedSharedDelivery", equalTo("DISABLED"));
    }

    @Test
    @Order(1)
    void putVdm_enablesAndRoundTripsThroughGetAccount() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"VdmAttributes":{"VdmEnabled":"ENABLED",
                      "DashboardAttributes":{"EngagementMetrics":"ENABLED"},
                      "GuardianAttributes":{"OptimizedSharedDelivery":"ENABLED"}}}
                    """)
        .when().put("/v2/email/account/vdm").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("ENABLED"))
                .body("VdmAttributes.DashboardAttributes.EngagementMetrics", equalTo("ENABLED"))
                .body("VdmAttributes.GuardianAttributes.OptimizedSharedDelivery", equalTo("ENABLED"));
    }

    @Test
    @Order(2)
    void putVdm_invalidEnum_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"MAYBE\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(3)
    void putVdm_missingVdmEnabled_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{}}")
        .when().put("/v2/email/account/vdm").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void putVdm_emptyBody_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
        .when().put("/v2/email/account/vdm").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(5)
    void putVdm_nonObjectDashboardAttributes_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\",\"DashboardAttributes\":\"oops\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(6)
    void putVdm_optionalAttributesDefaultToDisabled() {
        // Only VdmEnabled is supplied; the optional dashboard/guardian members default to DISABLED.
        // Also restores this region's VDM state to DISABLED.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("DISABLED"))
                .body("VdmAttributes.DashboardAttributes.EngagementMetrics", equalTo("DISABLED"))
                .body("VdmAttributes.GuardianAttributes.OptimizedSharedDelivery", equalTo("DISABLED"));
    }
}
