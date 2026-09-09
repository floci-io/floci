package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for the SES v2 tenant CRUD (Phase 1): {@code POST /v2/email/tenants} and the
 * RPC-style {@code /tenants/get}, {@code /tenants/list}, {@code /tenants/delete}. Shapes, id/ARN
 * formats, the ENABLED default, and the validation/duplicate/not-found errors are verified against
 * real AWS.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-it-tenant";

    @Test
    @Order(1)
    void createTenant_returnsGeneratedIdArnAndEnabledStatus() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"floci\"}]}")
        .when().post("/v2/email/tenants").then().statusCode(200)
                .body("TenantName", equalTo(TENANT))
                .body("TenantId", matchesPattern("tn-[0-9a-f]{30}"))
                .body("TenantArn", startsWith("arn:aws:ses:us-east-1:000000000000:tenant/" + TENANT + "/tn-"))
                .body("SendingStatus", equalTo("ENABLED"))
                .body("Tags[0].Key", equalTo("team"))
                .body("CreatedTimestamp", notNullValue());
    }

    @Test
    @Order(2)
    void getTenant_returnsTenant() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\"}")
        .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.TenantName", equalTo(TENANT))
                .body("Tenant.SendingStatus", equalTo("ENABLED"))
                .body("Tenant.TenantId", startsWith("tn-"));
    }

    @Test
    @Order(3)
    void listTenants_includesTenantInfoSubset() {
        given().contentType("application/json").header("Authorization", AUTH)
        .when().post("/v2/email/tenants/list").then().statusCode(200)
                .body("Tenants.find { it.TenantName == '" + TENANT + "' }.TenantId", startsWith("tn-"))
                .body("Tenants.find { it.TenantName == '" + TENANT + "' }.SendingStatus", equalTo(null));
    }

    @Test
    @Order(4)
    void createTenant_duplicate_returnsAlreadyExists() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\"}")
        .when().post("/v2/email/tenants").then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                .body("message", startsWith("Tenant with name " + TENANT + " already exists in account"));
    }

    @Test
    @Order(5)
    void deleteTenant_removesIt() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\"}")
        .when().post("/v2/email/tenants/delete").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\"}")
        .when().post("/v2/email/tenants/get").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The requested tenant <" + TENANT + "> does not exist."));
    }

    @Test
    @Order(6)
    void deleteTenant_missing_returnsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"ghost-tenant\"}")
        .when().post("/v2/email/tenants/delete").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void createTenant_invalidName_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"bad name!\"}")
        .when().post("/v2/email/tenants").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", startsWith("Invalid tenant name <bad name!>:"));
    }

    @Test
    @Order(8)
    void createTenant_tooLongName_returnsBadRequest() {
        String longName = "x".repeat(65);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + longName + "\"}")
        .when().post("/v2/email/tenants").then().statusCode(400)
                .body("message", equalTo("TenantName cannot exceed 64 characters."));
    }

    @Test
    @Order(9)
    void createTenant_invalidTag_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"tag-tenant\",\"Tags\":[{\"Key\":\"\",\"Value\":\"v\"}]}")
        .when().post("/v2/email/tenants").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(10)
    void getTenant_emptyName_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"\"}")
        .when().post("/v2/email/tenants/get").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(11)
    void listTenants_malformedBody_returnsSerializationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{ not json")
        .when().post("/v2/email/tenants/list").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(12)
    void createTenant_nonStringTagMember_returnsSerializationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"nonstring-tag\",\"Tags\":[{\"Key\":123,\"Value\":true}]}")
        .when().post("/v2/email/tenants").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }
}
