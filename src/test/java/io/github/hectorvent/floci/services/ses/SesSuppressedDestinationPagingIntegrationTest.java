package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of {@code ListSuppressedDestinations}, on the account list and on a tenant's. Runs in its
 * own region so the destinations created here are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSuppressedDestinationPagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/me-south-1/ses/aws4_request";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-north-1/ses/aws4_request";
    private static final String PATH = "/v2/email/suppression/addresses";
    private static final String ADDRESSES = "SuppressedDestinationSummaries.EmailAddress";
    private static final String TENANT = "page-tenant";

    // Put in address order, so oldest first and the address tie-break agree whatever the clock does.
    @Test
    @Order(1)
    void suppressDestinations() {
        put("page-a@example.com", "BOUNCE", null);
        put("page-b@example.com", "COMPLAINT", null);
        put("page-c@example.com", "BOUNCE", null);
        put("page-d@example.com", "BOUNCE", null);
    }

    @Test
    @Order(2)
    void walksOldestFirstAndEndsOnANullToken() {
        String token = v2().queryParam("PageSize", 1)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("page-a@example.com"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        v2().queryParam("PageSize", 3).queryParam("NextToken", token)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("page-b@example.com", "page-c@example.com", "page-d@example.com"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void theReasonFilterIsAppliedBeforeThePageIsCut() {
        String token = v2().queryParam("Reason", "BOUNCE").queryParam("PageSize", 2)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("page-a@example.com", "page-c@example.com"))
                .extract().path("NextToken");

        v2().queryParam("Reason", "BOUNCE").queryParam("PageSize", 2).queryParam("NextToken", token)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("page-d@example.com"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(4)
    void aTokenIsBoundToItsFilterAndRegion() {
        String token = v2().queryParam("Reason", "BOUNCE").queryParam("PageSize", 1)
        .when().get(PATH).then().statusCode(200).extract().path("NextToken");

        assertTokenRefused(v2().queryParam("NextToken", token));
        assertTokenRefused(v2().queryParam("Reason", "COMPLAINT").queryParam("NextToken", token));
        assertTokenRefused(given().header("Authorization", OTHER_REGION_AUTH)
                .queryParam("Reason", "BOUNCE").queryParam("NextToken", token));
    }

    @Test
    @Order(5)
    void outOfRangeSizesAndUnreadableTokensAreRefused() {
        for (int size : new int[] {0, 1001}) {
            v2().queryParam("PageSize", size)
            .when().get(PATH).then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo(
                            "Page size " + size + " is invalid, expected a number between 1 and 1000"));
        }
        assertTokenRefused(v2().queryParam("NextToken", "garbage"));
        assertTokenRefused(v2().queryParam("NextToken", ""));
        v2().queryParam("PageSize", 0).queryParam("NextToken", "garbage")
        .when().get(PATH).then().statusCode(400)
                .body("message", equalTo("Page size 0 is invalid, expected a number between 1 and 1000"));
    }

    @Test
    @Order(6)
    void aTenantListPagesOnItsOwnTokens() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"" + TENANT + "\"}")
        .when().post("/v2/email/tenants").then().statusCode(200);
        put("tenant-a@example.com", "BOUNCE", TENANT);
        put("tenant-b@example.com", "BOUNCE", TENANT);

        String tenantToken = v2().queryParam("TenantName", TENANT).queryParam("PageSize", 1)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("tenant-a@example.com"))
                .extract().path("NextToken");
        v2().queryParam("TenantName", TENANT).queryParam("NextToken", tenantToken)
        .when().get(PATH).then().statusCode(200)
                .body(ADDRESSES, contains("tenant-b@example.com"))
                .body("NextToken", nullValue());

        assertTokenRefused(v2().queryParam("NextToken", tenantToken));
        String accountToken = v2().queryParam("PageSize", 1)
        .when().get(PATH).then().statusCode(200).extract().path("NextToken");
        assertTokenRefused(v2().queryParam("TenantName", TENANT).queryParam("NextToken", accountToken));
    }

    private static void assertTokenRefused(RequestSpecification request) {
        request.when().get(PATH).then().statusCode(400)
                .body("__type", equalTo("InvalidNextTokenException"))
                .body("message", equalTo("Token is invalid."));
    }

    private static RequestSpecification v2() {
        return given().header("Authorization", AUTH);
    }

    private static void put(String address, String reason, String tenant) {
        String tenantMember = tenant == null ? "" : ",\"TenantName\":\"" + tenant + "\"";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + address + "\",\"Reason\":\"" + reason + "\"" + tenantMember + "}")
        .when().put(PATH).then().statusCode(200);
    }
}
