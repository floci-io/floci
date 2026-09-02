package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests for the tag operations on TENANT ARNs (the follow-up gap noted on the tenant
 * tracking issue). Probe-confirmed quirks: the ARN is resolved by the TenantId segment ALONE (the
 * name segment is never matched), tags are TenantId-scoped (a recreated same-name tenant's old ARN
 * 404s), the not-found message carries AWS's own missing space ("name: <name>with tenantId:"), the
 * tag list renders ordered by key, and the generic cross-account/cross-region tag semantics apply.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantTagV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-tag-tenant";
    private static final String ARN_PREFIX = "arn:aws:ses:us-east-1:000000000000:";

    private static String tenantArn;
    private static String tenantId;

    private static io.restassured.specification.RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    @Test
    @Order(1)
    void createTenantWithTags_visibleThroughListTagsForResource() {
        JsonPath created = v2()
                .body("{\"TenantName\":\"" + TENANT + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"floci\"}]}")
                .when().post("/v2/email/tenants").then().statusCode(200)
                .extract().jsonPath();
        tenantArn = created.getString("TenantArn");
        tenantId = created.getString("TenantId");

        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(200)
                .body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("team"));
    }

    @Test
    @Order(2)
    void tagUntag_lifecycle_sortedByKey_reflectsIntoGetTenant() {
        v2().body("{\"ResourceArn\":\"" + tenantArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .when().post("/v2/email/tags").then().statusCode(200);
        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(200)
                .body("Tags", hasSize(2))
                // Ordered by key: env before team (probe-confirmed).
                .body("Tags[0].Key", equalTo("env"))
                .body("Tags[1].Key", equalTo("team"));

        // Re-tagging an existing key replaces its value.
        v2().body("{\"ResourceArn\":\"" + tenantArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]}")
                .when().post("/v2/email/tags").then().statusCode(200);
        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(200)
                .body("Tags[0].Value", equalTo("prod"));

        // Untag removes the key; a nonexistent key is a silent success.
        v2().when().delete("/v2/email/tags?ResourceArn=" + tenantArn + "&TagKeys=env")
                .then().statusCode(200);
        v2().when().delete("/v2/email/tags?ResourceArn=" + tenantArn + "&TagKeys=ghost-key")
                .then().statusCode(200);

        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.Tags", hasSize(1))
                .body("Tenant.Tags[0].Key", equalTo("team"));
    }

    @Test
    @Order(3)
    void arnResolution_isByTenantIdAlone() {
        // A wrong name with a valid id resolves that id's tenant.
        v2().when().get("/v2/email/tags?ResourceArn="
                        + ARN_PREFIX + "tenant/wrong-name/" + tenantId)
                .then().statusCode(200)
                .body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("team"));

        // A valid name with a wrong id is a 404, with AWS's own missing space in the message.
        v2().when().get("/v2/email/tags?ResourceArn="
                        + ARN_PREFIX + "tenant/" + TENANT + "/tn-000000000000000000000000000000")
                .then().statusCode(404)
                .body("message", equalTo("No Tenant present with name: " + TENANT
                        + "with tenantId: tn-000000000000000000000000000000"));

        // An ARN without the id segment parses as a null name and the segment as the id.
        v2().when().get("/v2/email/tags?ResourceArn=" + ARN_PREFIX + "tenant/" + TENANT)
                .then().statusCode(404)
                .body("message", equalTo("No Tenant present with name: nullwith tenantId: " + TENANT));
    }

    @Test
    @Order(4)
    void crossAccountAndCrossRegion_matchTheGenericTagSemantics() {
        v2().when().get("/v2/email/tags?ResourceArn="
                        + tenantArn.replace(":000000000000:", ":111111111111:"))
                .then().statusCode(400)
                .body("message", equalTo(
                        "Operations on a resource created in a different account is not allowed"));

        // Matches the observable behavior of the other taggable types: existence is checked against
        // the signing region, then a mismatched-region ARN yields an empty tag set (for tenants the
        // tags live on the tenant record itself; the empty result comes from the dispatch's
        // explicit region comparison).
        v2().when().get("/v2/email/tags?ResourceArn="
                        + tenantArn.replace(":us-east-1:", ":eu-west-1:"))
                .then().statusCode(200)
                .body("Tags", hasSize(0));

        // Tag and Untag take the generic pre-dispatch region guard instead: 400 with the same
        // wording as the other six taggable types, probe-confirmed on a tenant ARN directly
        // (the guard fires before any tenant resolution, so nothing is mutated).
        String crossRegionArn = tenantArn.replace(":us-east-1:", ":eu-west-1:");
        v2().body("{\"ResourceArn\":\"" + crossRegionArn
                        + "\",\"Tags\":[{\"Key\":\"x\",\"Value\":\"y\"}]}")
                .when().post("/v2/email/tags").then().statusCode(400)
                .body("message", equalTo("Failed to tag resource"));
        v2().when().delete("/v2/email/tags?ResourceArn=" + crossRegionArn + "&TagKeys=team")
                .then().statusCode(400)
                .body("message", equalTo("Failed to untag resource"));

        // The guarded calls left the tenant's tags untouched.
        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(200)
                .body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("team"));
    }

    @Test
    @Order(5)
    void tags_areTenantIdScoped_acrossRecreation() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(404)
                .body("message", equalTo("No Tenant present with name: " + TENANT
                        + "with tenantId: " + tenantId));

        JsonPath recreated = v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200)
                .extract().jsonPath();
        v2().when().get("/v2/email/tags?ResourceArn=" + recreated.getString("TenantArn"))
                .then().statusCode(200)
                .body("Tags", hasSize(0));
        v2().when().get("/v2/email/tags?ResourceArn=" + tenantArn).then().statusCode(404);

        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
    }
}
