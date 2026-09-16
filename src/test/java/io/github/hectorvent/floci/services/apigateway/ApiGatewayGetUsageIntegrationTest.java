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
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code GetUsage}.
 *
 * <p>Envelope captured from real API Gateway: {@code values} maps an API key id to one pair per day
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
    void aPlanWithNoKeysReportsAnEmptyValuesMap() {
        String planId = createUsagePlan("usage-empty");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-13")
                .then().statusCode(200)
                .body("usagePlanId", equalTo(planId))
                .body("startDate", equalTo("2026-09-01"))
                .body("endDate", equalTo("2026-09-13"))
                .body("values", anEmptyMap());
    }

    // API key ids are random and can start with a digit, which a dotted GPath parses as a number.
    // Every lookup into values therefore uses bracket notation.
    @Test
    void eachKeyGetsOnePairPerDayOfTheInclusiveRange() {
        String planId = createUsagePlan("usage-range");
        String keyId = createApiKeyOnPlan(planId, "usage-range-key");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-13")
                .then().statusCode(200)
                // 1 to 13 September inclusive is 13 days, matching real API Gateway.
                .body("values['" + keyId + "']", hasSize(13))
                .body("values['" + keyId + "'][0]", hasSize(2))
                .body("values['" + keyId + "'][0][0]", equalTo(0))
                .body("values['" + keyId + "'][0][1]", equalTo(0));
    }

    @Test
    void aSingleDayRangeIsOnePair() {
        String planId = createUsagePlan("usage-oneday");
        String keyId = createApiKeyOnPlan(planId, "usage-oneday-key");

        given().when().get("/usageplans/" + planId + "/usage?startDate=2026-09-05&endDate=2026-09-05")
                .then().statusCode(200)
                .body("values['" + keyId + "']", hasSize(1));
    }

    @Test
    void theKeyIdFilterNarrowsToOneKey() {
        String planId = createUsagePlan("usage-filter");
        String first = createApiKeyOnPlan(planId, "usage-filter-a");
        createApiKeyOnPlan(planId, "usage-filter-b");

        given().when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&keyId=" + first)
                .then().statusCode(200)
                .body("values", aMapWithSize(1))
                .body("values['" + first + "']", hasSize(2));
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

    @Test
    void pagingCrossesAKeyBoundaryAndRoundTripsThePosition() {
        String planId = createUsagePlan("usage-paged");
        for (int i = 0; i < 3; i++) {
            createApiKeyOnPlan(planId, "usage-paged-" + i);
        }

        String position = given()
                .when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&limit=2")
                .then().statusCode(200)
                .body("values", aMapWithSize(2))
                .body("position", notNullValue())
                .extract().path("position");

        given()
                .when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&limit=2&position=" + position)
                .then().statusCode(200)
                .body("values", aMapWithSize(1))
                // No token on the terminal page, matching the capture from real AWS.
                .body("position", nullValue());
    }

    @Test
    void aSinglePageCarriesNoPosition() {
        String planId = createUsagePlan("usage-onepage");
        createApiKeyOnPlan(planId, "usage-onepage-key");

        given()
                .when().get("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-02")
                .then().statusCode(200)
                .body("values", aMapWithSize(1))
                .body("position", nullValue());
    }

    @Test
    void anUnrecognisedPositionIsRejected() {
        String planId = createUsagePlan("usage-badpos");
        createApiKeyOnPlan(planId, "usage-badpos-key");

        given()
                .when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&position=nonsense")
                .then().statusCode(400)
                .body(containsString("Invalid position parameter"));
    }

    @Test
    void aLimitBelowOneIsRejected() {
        String planId = createUsagePlan("usage-zerolimit");

        given()
                .when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&limit=0")
                .then().statusCode(400)
                .body(containsString("Invalid limit parameter"));
    }

    @Test
    void aLimitAboveTheDocumentedMaximumIsAccepted() {
        String planId = createUsagePlan("usage-biglimit");
        createApiKeyOnPlan(planId, "usage-biglimit-key");

        // Probed against real API Gateway: every value from 500 up to Integer.MAX_VALUE is accepted
        // without error, so the documented maximum of 500 is not a rejection boundary.
        for (String limit : new String[] {"500", "501", "100000", "2147483647"}) {
            given()
                    .when().get("/usageplans/" + planId + "/usage"
                            + "?startDate=2026-09-01&endDate=2026-09-02&limit=" + limit)
                    .then().statusCode(200)
                    .body("values", aMapWithSize(1));
        }
    }

    @Test
    void aNegativeLimitIsRejected() {
        String planId = createUsagePlan("usage-neglimit");

        // Real API Gateway answers a negative limit with an InternalFailure. That is a fault, not a
        // contract, so this rejects rather than reproducing a 500.
        given()
                .when().get("/usageplans/" + planId + "/usage"
                        + "?startDate=2026-09-01&endDate=2026-09-02&limit=-1")
                .then().statusCode(400)
                .body(containsString("Invalid limit parameter"));
    }
}
