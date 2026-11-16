package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

    private static void createAuthorizer(String apiId, String name) {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"type\":\"TOKEN\","
                        + "\"authorizerUri\":\"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                        + "arn:aws:lambda:us-east-1:000000000000:function:f/invocations\","
                        + "\"identitySource\":\"method.request.header.Authorization\"}")
                .when().post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201);
    }

    @Test
    void anIntegerLimitStillPagesAndABlankLimitUsesTheDefault() {
        String apiId = createRestApi();
        createAuthorizer(apiId, "first");
        createAuthorizer(apiId, "second");

        given().when().get("/restapis/" + apiId + "/authorizers?limit=1")
                .then().statusCode(200)
                .body("item", hasSize(1))
                .body("position", notNullValue());
        given().when().get("/restapis/" + apiId + "/authorizers?limit=")
                .then().statusCode(200)
                .body("item", hasSize(2))
                .body("position", nullValue());
    }
}
