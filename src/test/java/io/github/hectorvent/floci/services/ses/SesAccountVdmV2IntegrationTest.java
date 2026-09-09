package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the SES V2 account VDM attributes: {@code PUT /v2/email/account/vdm}
 * (PutAccountVdmAttributes) and the {@code GET /v2/email/account} (GetAccount) round-trip. VDM is
 * account/region-scoped, so this uses an isolated region and leaves it DISABLED at the end. Shapes
 * and defaults are verified against real AWS: VDM is opt-in and defaults to DISABLED, and the
 * Dashboard/Guardian sub-attributes are only returned while VdmEnabled is ENABLED.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesAccountVdmV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-central-1/ses/aws4_request";

    @Test
    @Order(0)
    void getAccount_vdmAttributesAbsentUntilConfigured() {
        // A region where PutAccountVdmAttributes was never called omits the VdmAttributes key
        // entirely; AWS only surfaces it once VDM has been configured for that region. Confirmed
        // against a live account: eu-west-1 and ap-northeast-1 (never configured) omit VdmAttributes,
        // while a region that was configured then disabled reports VdmEnabled DISABLED.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes", nullValue());
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
    void putVdm_nonStringVdmEnabled_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":true}}")
        .when().put("/v2/email/account/vdm").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void putVdm_enabledWithoutNested_defaultsNestedToDisabled() {
        // Enabling VDM without supplying the optional members: AWS surfaces both nested objects with
        // their DISABLED defaults once VdmEnabled is ENABLED.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("ENABLED"))
                .body("VdmAttributes.DashboardAttributes.EngagementMetrics", equalTo("DISABLED"))
                .body("VdmAttributes.GuardianAttributes.OptimizedSharedDelivery", equalTo("DISABLED"));
    }

    @Test
    @Order(8)
    void putVdm_disabled_omitsNestedAttributes() {
        // Disabling VDM drops the nested objects again, and restores this region to DISABLED.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("DISABLED"))
                .body("VdmAttributes.DashboardAttributes", nullValue())
                .body("VdmAttributes.GuardianAttributes", nullValue());
    }
}
