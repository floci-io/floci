package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for SES v2 tenant suppression (Phase 3): {@code PutTenantSuppressionAttributes}
 * on its singular {@code /v2/email/tenant/suppression} route, and the {@code TenantName}-scoped
 * variants of the suppression-list operations. Probe-confirmed behavior: the attribute pair is
 * all-or-nothing (a bare TenantName clears it), each tenant's list is fully separate from the
 * account list, the tenant-scoped delete is not idempotent, and {@code DeleteTenant} cascades the
 * entries.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantSuppressionV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-sup-tenant";
    private static final String TENANT_ADDR = "floci-sup-tenant@example.com";
    private static final String ACCOUNT_ADDR = "floci-sup-acct@example.com";

    private static io.restassured.specification.RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    @Test
    @Order(1)
    void createTenant_withSuppressionAttributes_rendersThem() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressionAttributes\":"
                        + "{\"SuppressedReasons\":[\"BOUNCE\"],\"SuppressionScope\":\"TENANT\"}}")
                .when().post("/v2/email/tenants").then().statusCode(200)
                .body("SuppressionAttributes.SuppressedReasons", contains("BOUNCE"))
                .body("SuppressionAttributes.SuppressionScope", equalTo("TENANT"));
    }

    @Test
    @Order(2)
    void putSuppressionAttributes_replacesAndClears() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":"
                        + "[\"BOUNCE\",\"COMPLAINT\"],\"SuppressionScope\":\"ACCOUNT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(200)
                .body("isEmpty()", equalTo(true));
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.SuppressionAttributes.SuppressedReasons", contains("BOUNCE", "COMPLAINT"))
                .body("Tenant.SuppressionAttributes.SuppressionScope", equalTo("ACCOUNT"));

        // An empty reason list with a scope is a valid state.
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":[],"
                        + "\"SuppressionScope\":\"TENANT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.SuppressionAttributes.SuppressedReasons", hasSize(0))
                .body("Tenant.SuppressionAttributes.SuppressionScope", equalTo("TENANT"));

        // A put with neither member clears the block; GetTenant renders the explicit null.
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.SuppressionAttributes", nullValue());
    }

    @Test
    @Order(3)
    void putSuppressionAttributes_errorMatrix() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":[\"BOUNCE\"]}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("SuppressedReasons cannot be specified without SuppressionScope."));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressionScope\":\"ACCOUNT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("SuppressionScope cannot be specified without SuppressedReasons."));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":[]}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("SuppressionScope is required when SuppressedReasons are "
                        + "provided. Valid values are: TENANT, ACCOUNT"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":[\"NOPE\"]}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'suppressedReasons' "
                        + "failed to satisfy constraint: Member must satisfy constraint: [Member must "
                        + "satisfy enum value set: [BOUNCE, COMPLAINT]]"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressionScope\":\"NOPE\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'suppressionScope' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[TENANT, ACCOUNT]"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"SuppressedReasons\":"
                        + "[\"BOUNCE\",\"BOUNCE\"],\"SuppressionScope\":\"ACCOUNT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("Each suppressed reason can only be specified at most once"));

        // Precedence: empty TenantName wins over enum errors; validation wins over existence. An
        // absent TenantName collapses to the same message (no Smithy not-null on this operation).
        v2().body("{\"TenantName\":\"\",\"SuppressedReasons\":[\"NOPE\"],\"SuppressionScope\":\"TENANT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("TenantName cannot be empty"));
        v2().body("{\"SuppressedReasons\":[\"BOUNCE\"],\"SuppressionScope\":\"TENANT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("TenantName cannot be empty"));
        v2().body("{\"TenantName\":\"ghost-tenant\",\"SuppressedReasons\":[\"BOUNCE\"]}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(400)
                .body("message", equalTo("SuppressedReasons cannot be specified without SuppressionScope."));
        v2().body("{\"TenantName\":\"ghost-tenant\",\"SuppressedReasons\":[\"BOUNCE\"],"
                        + "\"SuppressionScope\":\"TENANT\"}")
                .when().post("/v2/email/tenant/suppression").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The requested tenant <ghost-tenant> does not exist."));
    }

    @Test
    @Order(4)
    void tenantSuppressionList_isSeparateFromAccountList() {
        v2().body("{\"EmailAddress\":\"floci-sup-acct@example.com\",\"Reason\":\"BOUNCE\"}")
                .when().put("/v2/email/suppression/addresses").then().statusCode(200);
        v2().body("{\"EmailAddress\":\"floci-sup-tenant@example.com\",\"Reason\":\"BOUNCE\","
                        + "\"TenantName\":\"" + TENANT + "\"}")
                .when().put("/v2/email/suppression/addresses").then().statusCode(200);

        v2().when().get("/v2/email/suppression/addresses/" + TENANT_ADDR + "?TenantName=" + TENANT)
                .then().statusCode(200)
                .body("SuppressedDestination.EmailAddress", equalTo("floci-sup-tenant@example.com"))
                .body("SuppressedDestination.TenantName", equalTo(TENANT))
                .body("SuppressedDestination.LastUpdateTime", notNullValue());
        // The account entry renders an explicit TenantName null.
        v2().when().get("/v2/email/suppression/addresses/" + ACCOUNT_ADDR).then().statusCode(200)
                .body("SuppressedDestination.TenantName", nullValue());

        v2().when().get("/v2/email/suppression/addresses/" + TENANT_ADDR).then().statusCode(404)
                .body("message", equalTo("Email address floci-sup-tenant@example.com does not exist "
                        + "on your suppression list."));
        v2().when().get("/v2/email/suppression/addresses/" + ACCOUNT_ADDR + "?TenantName=" + TENANT)
                .then().statusCode(404)
                .body("message", equalTo("Email address floci-sup-acct@example.com does not exist "
                        + "on your tenant suppression list."));
    }

    @Test
    @Order(5)
    void tenantSuppressionList_listAndFilter() {
        v2().when().get("/v2/email/suppression/addresses?TenantName=" + TENANT).then().statusCode(200)
                .body("SuppressedDestinationSummaries", hasSize(1))
                .body("SuppressedDestinationSummaries[0].EmailAddress",
                        equalTo("floci-sup-tenant@example.com"));
        v2().when().get("/v2/email/suppression/addresses?TenantName=" + TENANT + "&Reason=COMPLAINT")
                .then().statusCode(200)
                .body("SuppressedDestinationSummaries", hasSize(0));
        v2().when().get("/v2/email/suppression/addresses?TenantName=ghost-tenant").then().statusCode(404)
                .body("message", equalTo("The requested tenant <ghost-tenant> does not exist."));
    }

    @Test
    @Order(6)
    void tenantSuppressionList_ghostTenantOnPut_andDeleteNotIdempotent() {
        v2().body("{\"EmailAddress\":\"x@example.com\",\"Reason\":\"BOUNCE\","
                        + "\"TenantName\":\"ghost-tenant\"}")
                .when().put("/v2/email/suppression/addresses").then().statusCode(404)
                .body("message", equalTo("The requested tenant <ghost-tenant> does not exist."));

        // Request validation precedes tenant existence: a bad member wins over the ghost tenant.
        v2().body("{\"EmailAddress\":\"\",\"Reason\":\"BOUNCE\",\"TenantName\":\"ghost-tenant\"}")
                .when().put("/v2/email/suppression/addresses").then().statusCode(400)
                .body("message", equalTo("EmailAddress is required."));
        v2().when().get("/v2/email/suppression/addresses?TenantName=ghost-tenant&Reason=NOPE")
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'reasons' failed to "
                        + "satisfy constraint: Member must satisfy constraint: [Member must satisfy "
                        + "enum value set: [BOUNCE, COMPLAINT]]"));

        v2().when().delete("/v2/email/suppression/addresses/" + TENANT_ADDR + "?TenantName=" + TENANT)
                .then().statusCode(200);
        // Unlike the resource associations, this delete is not idempotent on AWS.
        v2().when().delete("/v2/email/suppression/addresses/" + TENANT_ADDR + "?TenantName=" + TENANT)
                .then().statusCode(404)
                .body("message", equalTo("Email address floci-sup-tenant@example.com does not exist "
                        + "on your tenant suppression list."));
    }

    @Test
    @Order(7)
    void deleteTenant_cascadesSuppressionEntries_recreationStartsClean() {
        v2().body("{\"EmailAddress\":\"floci-sup-tenant@example.com\",\"Reason\":\"COMPLAINT\","
                        + "\"TenantName\":\"" + TENANT + "\"}")
                .when().put("/v2/email/suppression/addresses").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);

        v2().when().get("/v2/email/suppression/addresses/" + TENANT_ADDR + "?TenantName=" + TENANT)
                .then().statusCode(404)
                .body("message", equalTo("The requested tenant <" + TENANT + "> does not exist."));

        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200)
                .body("SuppressionAttributes", nullValue());
        v2().when().get("/v2/email/suppression/addresses?TenantName=" + TENANT).then().statusCode(200)
                .body("SuppressedDestinationSummaries", hasSize(0));

        // Cleanup: the account entry and the tenant.
        v2().when().delete("/v2/email/suppression/addresses/" + ACCOUNT_ADDR).then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
    }
}
