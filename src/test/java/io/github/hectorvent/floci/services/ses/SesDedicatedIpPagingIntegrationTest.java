package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of {@code ListDedicatedIpPools} and {@code GetDedicatedIps}. Runs in its own region so
 * the pools created here are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesDedicatedIpPagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-northeast-3/ses/aws4_request";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-north-1/ses/aws4_request";
    private static final String POOLS = "/v2/email/dedicated-ip-pools";
    private static final String IPS = "/v2/email/dedicated-ips";

    @Test
    @Order(1)
    void createPoolsOutOfNameOrder() {
        for (String name : new String[] {"page-b", "page-c", "page-a"}) {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"PoolName\": \"" + name + "\"}")
            .when().post(POOLS).then().statusCode(200);
        }
    }

    @Test
    @Order(2)
    void pools_walkByNameAndEndOnANullToken() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get(POOLS).then().statusCode(200)
                .body("DedicatedIpPools", contains("page-a"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        given().header("Authorization", AUTH).queryParam("PageSize", 2).queryParam("NextToken", token)
        .when().get(POOLS).then().statusCode(200)
                .body("DedicatedIpPools", contains("page-b", "page-c"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void pools_outOfRangeSizesAndUnreadableTokensAreRefused() {
        for (int size : new int[] {0, 1001}) {
            given().header("Authorization", AUTH).queryParam("PageSize", size)
            .when().get(POOLS).then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("The page size must be in [1, 1000] range."));
        }
        given().header("Authorization", AUTH).queryParam("NextToken", "garbage")
        .when().get(POOLS).then().statusCode(400)
                .body("message", equalTo("Invalid next token."));
        given().header("Authorization", AUTH).queryParam("NextToken", "")
        .when().get(POOLS).then().statusCode(200)
                .body("DedicatedIpPools", contains("page-a", "page-b", "page-c"));
    }

    @Test
    @Order(4)
    void aPoolTokenIsRefusedInAnotherRegionAndByTheIpList() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get(POOLS).then().statusCode(200).extract().path("NextToken");

        given().header("Authorization", OTHER_REGION_AUTH).queryParam("NextToken", token)
        .when().get(POOLS).then().statusCode(400)
                .body("message", equalTo("Invalid next token."));
        given().header("Authorization", AUTH).queryParam("NextToken", token)
        .when().get(IPS).then().statusCode(400)
                .body("message", equalTo("Invalid next token."));
    }

    @Test
    @Order(5)
    void dedicatedIps_areEmptyButStillCheckThePageSize() {
        given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get(IPS).then().statusCode(200)
                .body("DedicatedIps", empty())
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
        given().header("Authorization", AUTH).queryParam("PageSize", 1001)
        .when().get(IPS).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The page size must be in [1, 1000] range."));
    }
}
