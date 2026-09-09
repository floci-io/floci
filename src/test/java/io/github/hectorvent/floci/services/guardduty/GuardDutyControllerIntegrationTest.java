package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the GuardDuty restJson1 detector lifecycle, organization readback, and isolation. */
@QuarkusTest
class GuardDutyControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void detectorCreateGetUpdateDeleteLifecycle() {
        String authorization = auth("000000000201", EAST);
        String detectorId = createDetector(authorization, """
                {"enable":true,"findingPublishingFrequency":"SIX_HOURS","tags":{"env":"compat"}}
                """);

        Response detector = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("SIX_HOURS"))
                .body("serviceRole", notNullValue())
                .body("tags.env", equalTo("compat"))
                .extract().response();
        assertTrue(detector.path("createdAt").toString().endsWith("Z"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enable\":false,\"findingPublishingFrequency\":\"ONE_HOUR\"}")
                .when()
                .post("/detector/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DISABLED"))
                .body("findingPublishingFrequency", equalTo("ONE_HOUR"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", equalTo(List.of(detectorId)));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
    }

    @Test
    void featureOrderIsPreservedOnReadBack() {
        String authorization = auth("000000000202", EAST);
        String detectorId = createDetector(authorization, """
                {"enable":true,"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]},
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"}
                ]}
                """);

        Response detector = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                detector.path("features.name"));
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                detector.path("features[0].additionalConfiguration.name"));
    }

    @Test
    void organizationConfigurationLifecycle() {
        String authorization = auth("000000000203", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true}");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(false))
                .body("memberAccountLimitReached", equalTo(false))
                .body("autoEnableOrganizationMembers", equalTo("NONE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"autoEnableOrganizationMembers":"ALL","features":[
                          {"name":"RUNTIME_MONITORING","autoEnable":"ALL","additionalConfiguration":[
                            {"name":"ECS_FARGATE_AGENT_MANAGEMENT","autoEnable":"ALL"},
                            {"name":"EC2_AGENT_MANAGEMENT","autoEnable":"ALL"},
                            {"name":"EKS_ADDON_MANAGEMENT","autoEnable":"NONE"}
                          ]}
                        ]}
                        """)
                .when()
                .post("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200);

        Response configuration = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(true))
                .body("autoEnableOrganizationMembers", equalTo("ALL"))
                .extract().response();
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                configuration.path("features[0].additionalConfiguration.name"));
    }

    @Test
    void organizationAdminAccountLifecycle() {
        String authorization = auth("000000000204", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/enable")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body("adminAccounts[0].adminAccountId", equalTo("111111111111"))
                .body("adminAccounts[0].adminStatus", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/disable")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/disable")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.ADMIN_ALREADY_DISABLED_MESSAGE));
    }

    @Test
    void detectorsAreIsolatedByAccountAndRegion() {
        String eastAccountA = auth("000000000205", EAST);
        String eastAccountB = auth("000000000206", EAST);
        String westAccountA = auth("000000000205", WEST);
        String detectorId = createDetector(eastAccountA, "{\"enable\":true}");

        given()
                .header("Authorization", eastAccountB)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));

        given()
                .header("Authorization", westAccountA)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));

        given()
                .header("Authorization", eastAccountA)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200);
    }

    @Test
    void secondCreateInSameAccountAndRegionIsRejected() {
        String authorization = auth("000000000207", EAST);
        createDetector(authorization, "{\"enable\":true}");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enable\":true}")
                .when()
                .post("/detector")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void detectorTagsAreServedThroughTheSharedTagsRoutes() {
        String authorization = auth("000000000208", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true,\"tags\":{\"env\":\"test\"}}");
        String arn = "arn:aws:guardduty:" + EAST + ":000000000208:detector/" + detectorId;

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"team\":\"security\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", equalTo("security"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "env")
                .when()
                .delete("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("security"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260215/" + region + "/guardduty/aws4_request";
    }

    private static String createDetector(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/detector")
                .then()
                .statusCode(200)
                .body("detectorId", notNullValue())
                .extract().path("detectorId");
    }
}
