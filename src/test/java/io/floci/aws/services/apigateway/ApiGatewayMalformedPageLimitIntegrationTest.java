package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A page size the SDK's typed setter cannot produce, so these are raw requests: a non-numeric or
 * out-of-range {@code limit} must reach the service and come back as a {@code BadRequestException}.
 */
@QuarkusTest
class ApiGatewayMalformedPageLimitIntegrationTest {

    private static final String[] MALFORMED = {"abc", "1.5", "99999999999"};

    private static String createRestApi() {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"malformed-page-limit\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static String createUsagePlan() {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"malformed-page-limit\"}")
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static void assertRejected(String path) {
        for (String limit : MALFORMED) {
            given().when().get(path + (path.contains("?") ? "&" : "?") + "limit=" + limit)
                    .then().statusCode(400)
                    .header("x-amzn-ErrorType", containsString("BadRequestException"))
                    .body("message", containsString("limit must be an integer"));
        }
    }

    @Test
    void getAuthorizersRejectsAMalformedLimit() {
        String apiId = createRestApi();

        assertRejected("/restapis/" + apiId + "/authorizers");
    }

    @Test
    void getDocumentationPartsRejectsAMalformedLimit() {
        String apiId = createRestApi();

        assertRejected("/restapis/" + apiId + "/documentation/parts");
    }

    @Test
    void getUsageRejectsAMalformedLimit() {
        String planId = createUsagePlan();

        assertRejected("/usageplans/" + planId + "/usage?startDate=2026-09-01&endDate=2026-09-02");
    }

    @Test
    void anIntegerLimitStillPages() {
        String apiId = createRestApi();

        given().when().get("/restapis/" + apiId + "/authorizers?limit=1")
                .then().statusCode(200)
                .body("item", hasSize(0))
                .body("position", equalTo(null));
    }
}
