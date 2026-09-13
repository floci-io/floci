package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code GetUsage}.
 *
 * <p>Envelope captured from real API Gateway: {@code items} maps an API key id to one pair per day
 * of the inclusive range, alongside {@code usagePlanId}, {@code startDate} and {@code endDate}, with
 * no {@code position} when there is no further page.
 *
 * <p>Each pair is {@code [used, remaining]}, not {@code [used, quota]}: on real API Gateway the
 * second element is the quota limit minus cumulative use. Both are zero here because nothing meters
 * requests per key and a usage plan stores no quota.
 */
@QuarkusTest
class ApiGatewayGetUsageIntegrationTest {

    private static String createUsagePlan(String name) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static String createApiKeyOnPlan(String planId, String keyName) {
        String keyId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + keyName + "\",\"enabled\":true}")
                .when().post("/apikeys")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"keyId\":\"" + keyId + "\",\"keyType\":\"API_KEY\"}")
                .when().post("/usageplans/" + planId + "/keys")
                .then().statusCode(201);
        return keyId;
    }

    @Test
    void aPlanWithNoKeysReportsAnEmptyItemsMap() {
        String planId = createUsagePlan("usage-empty");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-13")
                .then().statusCode(200)
                .body("usagePlanId", equalTo(planId))
                .body("startDate", equalTo("2026-09-01"))
                .body("endDate", equalTo("2026-09-13"))
                .body("items", anEmptyMap());
    }

    @Test
    void eachKeyGetsOnePairPerDayOfTheInclusiveRange() {
        String planId = createUsagePlan("usage-range");
        String keyId = createApiKeyOnPlan(planId, "usage-range-key");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-13")
                .then().statusCode(200)
                // 1 to 13 September inclusive is 13 days, matching real API Gateway.
                .body("items." + keyId, hasSize(13))
                .body("items." + keyId + "[0]", hasSize(2))
                .body("items." + keyId + "[0][0]", equalTo(0))
                .body("items." + keyId + "[0][1]", equalTo(0));
    }

    @Test
    void aSingleDayRangeIsOnePair() {
        String planId = createUsagePlan("usage-oneday");
        String keyId = createApiKeyOnPlan(planId, "usage-oneday-key");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-05&endDate=2026-09-05")
                .then().statusCode(200)
                .body("items." + keyId, hasSize(1));
    }

    @Test
    void theKeyIdFilterNarrowsToOneKey() {
        String planId = createUsagePlan("usage-filter");
        String first = createApiKeyOnPlan(planId, "usage-filter-a");
        createApiKeyOnPlan(planId, "usage-filter-b");

        given().when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&keyId=" + first)
                .then().statusCode(200)
                .body("items", aMapWithSize(1))
                .body("items." + first, hasSize(2));
    }

    @Test
    void anUnknownUsagePlanIsNotFound() {
        given().when().get("/usageplans/nosuchplan/usage?startDate=2026-09-01&endDate=2026-09-13")
                .then().statusCode(404);
    }

    @Test
    void anEndDateBeforeTheStartDateIsRejected() {
        String planId = createUsagePlan("usage-reversed");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-13&endDate=2026-09-01")
                .then().statusCode(400)
                .body(containsString("Usage end date must be after start date"));
    }

    @Test
    void aMissingStartDateIsRejected() {
        String planId = createUsagePlan("usage-nodate");

        given().when().get("/usageplans/" + planId + "/usage?endDate=2026-09-13")
                .then().statusCode(400)
                .body(containsString("startDate"));
    }

    @Test
    void aMalformedDateIsRejected() {
        String planId = createUsagePlan("usage-baddate");

        given().when().get("/usageplans/" + planId + "/usage?startDate=13-09-2026&endDate=2026-09-13")
                .then().statusCode(400)
                .body(containsString("YYYY-MM-DD"));
    }
}
