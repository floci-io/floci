package io.github.hectorvent.floci.services.cloudwatch.dashboards;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * The dashboard operations reach the emulator over the Query protocol - that is what the
 * Terraform AWS provider speaks to CloudWatch, and the route that used to answer
 * "Operation PutDashboard is not supported by CloudWatch Query".
 */
@QuarkusTest
class CloudWatchDashboardsIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";

    private static final String JSON_1_0 = "application/x-amz-json-1.0";

    @BeforeAll
    static void registerJsonParser() {
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
    }

    // RestAssured has no built-in serializer for the x-amz-json content types; send raw text.
    private static RequestSpecification json(String target, String body) {
        return given()
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)))
                .contentType(JSON_1_0)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + target)
                .body(body);
    }

    private static final String BODY =
            "{\"widgets\":[{\"type\":\"metric\",\"properties\":{\"title\":\"a > b\"}}]}";

    private void putDashboard(String name, String body) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "PutDashboard")
            .formParam("DashboardName", name)
            .formParam("DashboardBody", body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    void putThenGetReturnsTheBodyVerbatim() {
        putDashboard("query-round-trip", BODY);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "GetDashboard")
            .formParam("DashboardName", "query-round-trip")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody", equalTo(BODY))
            .body("GetDashboardResponse.GetDashboardResult.DashboardArn",
                    containsString(":dashboard/query-round-trip"));
    }

    @Test
    void getUnknownDashboardReturnsResourceNotFound() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "GetDashboard")
            .formParam("DashboardName", "no-such-dashboard")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
    }

    @Test
    void listDashboardsHonoursTheNamePrefix() {
        putDashboard("prefixed-one", BODY);
        putDashboard("prefixed-two", BODY);
        putDashboard("unprefixed", BODY);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "ListDashboards")
            .formParam("DashboardNamePrefix", "prefixed-")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListDashboardsResponse.ListDashboardsResult.DashboardEntries.member.DashboardName",
                    hasItem("prefixed-one"))
            .body("ListDashboardsResponse.ListDashboardsResult.DashboardEntries.member.DashboardName",
                    not(hasItem("unprefixed")));
    }

    @Test
    void deleteDashboardsRemovesEveryNamedDashboard() {
        putDashboard("delete-a", BODY);
        putDashboard("delete-b", BODY);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "DeleteDashboards")
            .formParam("DashboardNames.member.1", "delete-a")
            .formParam("DashboardNames.member.2", "delete-b")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "GetDashboard")
            .formParam("DashboardName", "delete-a")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    // The AWS CLI and SDK v3 send the same operations as JSON with an X-Amz-Target header,
    // which the emulator dispatches to CloudWatchMetricsJsonHandler instead.
    @Test
    void theSameOperationsAreServedOverTheJsonProtocol() {
        json("PutDashboard", "{\"DashboardName\": \"json-round-trip\", \"DashboardBody\": \"{}\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        json("GetDashboard", "{\"DashboardName\": \"json-round-trip\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DashboardBody", equalTo("{}"));
    }
    /**
     * Tags supplied on PutDashboard have to be readable afterwards, and CloudWatch's tag
     * operations take one ARN for every resource it owns, so the handler has to route a dashboard
     * ARN to the dashboards service rather than the alarm store that answered before.
     */
    @Test
    void tagsSuppliedOnPutAreReadableThroughListTagsForResource() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "PutDashboard")
            .formParam("DashboardName", "tagged")
            .formParam("DashboardBody", BODY)
            .formParam("Tags.member.1.Key", "team")
            .formParam("Tags.member.1.Value", "platform")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String arn = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "GetDashboard")
            .formParam("DashboardName", "tagged")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("GetDashboardResponse.GetDashboardResult.DashboardArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceARN", arn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>team</Key>"))
            .body(containsString("<Value>platform</Value>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "UntagResource")
            .formParam("ResourceARN", arn)
            .formParam("TagKeys.member.1", "team")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceARN", arn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Key>team</Key>")));
    }

}
