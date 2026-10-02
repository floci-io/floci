package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of the v2 {@code ListTenants}, {@code ListTenantResources} and {@code ListResourceTenants}
 * lists as probed on real SES: the orders (tenants by name, a tenant's resources by ARN, a resource's
 * tenants in association order), the Smithy validation messages, the lookup and filter coming before
 * the page validation, and a token bound to the request it came from. Runs in its own region so the
 * tenants created here are the whole list; the test-scope ticking clock separates the association
 * stamps.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantPagingIntegrationTest {

    private static final String REGION = "af-south-1";
    // Only a token is sent there and it is refused, so no state lands in this region.
    private static final String OTHER_REGION = "eu-west-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String ARN_PREFIX = "arn:aws:ses:" + REGION + ":000000000000:";
    private static final String PAGE_SIZE_BELOW_ONE = "Value '0' at 'pageSize' failed to satisfy constraint: "
            + "Member must have value greater than or equal to 1";
    private static final String PAGE_SIZE_ABOVE_BOUND = "Value '101' at 'pageSize' failed to satisfy constraint: "
            + "Member must have value less than or equal to 100";
    private static final String EMPTY_TOKEN = "Value '' at 'nextToken' failed to satisfy constraint: "
            + "Member must have length greater than or equal to 1";

    private static RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    @Test
    @Order(1)
    void createTenantsAndResourcesOutOfOrder() {
        // Tenants are created out of name order, and associated to the shared identity in yet
        // another order, so a by-name walk and an association-order walk cannot both pass by luck.
        for (String tenant : new String[] {"page-c", "page-a", "page-b"}) {
            v2().body("{\"TenantName\":\"" + tenant + "\"}")
            .when().post("/v2/email/tenants").then().statusCode(200);
        }
        v2().body("{\"EmailIdentity\":\"shared.page.test\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        for (String tenant : new String[] {"page-b", "page-c", "page-a"}) {
            associate(tenant, ARN_PREFIX + "identity/shared.page.test");
        }
        for (String identity : new String[] {"c.page.test", "a.page.test", "b.page.test"}) {
            v2().body("{\"EmailIdentity\":\"" + identity + "\"}")
            .when().post("/v2/email/identities").then().statusCode(200);
            associate("page-a", ARN_PREFIX + "identity/" + identity);
        }
    }

    @Test
    @Order(2)
    void listTenants_walksByNameAndEndsOnANullToken() {
        String token = v2().body("{\"PageSize\":1}")
        .when().post("/v2/email/tenants/list").then().statusCode(200)
                .body("Tenants.TenantName", contains("page-a"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        v2().body("{\"PageSize\":5,\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/tenants/list").then().statusCode(200)
                .body("Tenants.TenantName", contains("page-b", "page-c"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void listTenantResources_walksByArn() {
        String token = v2().body("{\"TenantName\":\"page-a\",\"PageSize\":1}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(200)
                .body("TenantResources.ResourceArn", contains(ARN_PREFIX + "identity/a.page.test"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        v2().body("{\"TenantName\":\"page-a\",\"PageSize\":10,\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(200)
                .body("TenantResources.ResourceArn", contains(ARN_PREFIX + "identity/b.page.test",
                        ARN_PREFIX + "identity/c.page.test", ARN_PREFIX + "identity/shared.page.test"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(4)
    void listResourceTenants_walksInAssociationOrder() {
        String token = v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/shared.page.test\",\"PageSize\":1}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("ResourceTenants.TenantName", contains("page-b"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/shared.page.test\",\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("ResourceTenants.TenantName", contains("page-c", "page-a"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(5)
    void tokensDoNotCrossBetweenTheThreeLists() {
        String tenantToken = v2().body("{\"PageSize\":1}")
        .when().post("/v2/email/tenants/list").then().statusCode(200).extract().path("NextToken");

        v2().body("{\"TenantName\":\"page-a\",\"NextToken\":\"" + tenantToken + "\"}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid Next Token"));
        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/shared.page.test\",\"NextToken\":\""
                + tenantToken + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));

        // The two association lists page the same records, so each must still refuse the other's token.
        String resourceToken = v2().body("{\"TenantName\":\"page-a\",\"PageSize\":1}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(200).extract().path("NextToken");
        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/shared.page.test\",\"NextToken\":\""
                + resourceToken + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
    }

    @Test
    @Order(6)
    void aTokenReplayedInAnotherRegionIsRefused() {
        String token = v2().body("{\"PageSize\":1}")
        .when().post("/v2/email/tenants/list").then().statusCode(200).extract().path("NextToken");

        given().contentType("application/json")
                .header("Authorization", AUTH.replace(REGION, OTHER_REGION))
                .body("{\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/tenants/list").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid Next Token"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lists")
    @Order(7)
    void outOfRangeSizesAndBadTokensAnswerTheSmithyMessages(String operation, String path, String subject) {
        v2().body("{" + subject + "\"PageSize\":0}")
        .when().post(path).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: " + PAGE_SIZE_BELOW_ONE));
        v2().body("{" + subject + "\"PageSize\":101}")
        .when().post(path).then().statusCode(400)
                .body("message", equalTo("1 validation error detected: " + PAGE_SIZE_ABOVE_BOUND));
        v2().body("{" + subject + "\"NextToken\":\"garbage\"}")
        .when().post(path).then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
        v2().body("{" + subject + "\"NextToken\":\"\"}")
        .when().post(path).then().statusCode(400)
                .body("message", equalTo("1 validation error detected: " + EMPTY_TOKEN));
        v2().body("{" + subject + "\"PageSize\":0,\"NextToken\":\"\"}")
        .when().post(path).then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: " + EMPTY_TOKEN + "; " + PAGE_SIZE_BELOW_ONE));
    }

    private static Stream<Arguments> lists() {
        return Stream.of(
                Arguments.of("ListTenants", "/v2/email/tenants/list", ""),
                Arguments.of("ListTenantResources", "/v2/email/tenants/resources/list",
                        "\"TenantName\":\"page-a\","),
                Arguments.of("ListResourceTenants", "/v2/email/resources/tenants/list",
                        "\"ResourceArn\":\"" + ARN_PREFIX + "identity/shared.page.test\","));
    }

    @Test
    @Order(8)
    void theLookupAndTheFilterPrecedeThePageValidation() {
        v2().body("{\"TenantName\":\"ghost-tenant\",\"PageSize\":0,\"NextToken\":\"\"}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        v2().body("{\"TenantName\":\"ghost-tenant\",\"Filter\":{\"RESOURCE_TYPE\":\"NOPE\"}}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        v2().body("{\"TenantName\":\"page-a\",\"Filter\":{\"RESOURCE_TYPE\":\"NOPE\"},\"PageSize\":0}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("message", equalTo("Invalid resource type NOPE specified."));

        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/ghost.page.test\",\"PageSize\":0}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(9)
    void aTokenIsBoundToItsTenantFilterAndResource() {
        String resourceToken = v2().body("{\"TenantName\":\"page-a\",\"PageSize\":1}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(200).extract().path("NextToken");
        v2().body("{\"TenantName\":\"page-b\",\"NextToken\":\"" + resourceToken + "\"}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
        v2().body("{\"TenantName\":\"page-a\",\"Filter\":{\"RESOURCE_TYPE\":\"identity\"},\"NextToken\":\""
                + resourceToken + "\"}")
        .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));

        String tenantToken = v2().body("{\"ResourceArn\":\"" + ARN_PREFIX
                + "identity/shared.page.test\",\"PageSize\":1}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(200).extract().path("NextToken");
        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/a.page.test\",\"NextToken\":\""
                + tenantToken + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
    }

    @Test
    @Order(10)
    void aTokenIsNotTakenForAResourceWhoseNameItsOwnStartsWith() {
        // The second name holds the ':' that ends the scope inside a token.
        for (String identity : new String[] {"colon.page.test", "colon.page.test:x"}) {
            v2().body("{\"EmailIdentity\":\"" + identity + "\"}")
            .when().post("/v2/email/identities").then().statusCode(200);
            associate("page-b", ARN_PREFIX + "identity/" + identity);
            associate("page-c", ARN_PREFIX + "identity/" + identity);
        }
        String longer = v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/colon.page.test:x\",\"PageSize\":1}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(200).extract().path("NextToken");

        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/colon.page.test\",\"NextToken\":\"" + longer + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "identity/colon.page.test:x\",\"NextToken\":\"" + longer + "\"}")
        .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("ResourceTenants.TenantName", contains("page-c"));
    }

    private static void associate(String tenant, String arn) {
        v2().body("{\"TenantName\":\"" + tenant + "\",\"ResourceArn\":\"" + arn + "\"}")
        .when().post("/v2/email/tenants/resources").then().statusCode(200);
    }
}
