package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class GuardDutyMembersIntegrationTest {
    private boolean enabledOrganizationAdmin;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * GuardDuty's organization admin accounts are shared by every test in the JVM, and Macie
     * answers the same {@code /admin} route, so an admin left behind here makes
     * {@code MacieOrganizationIntegrationTest.sharedAdminRouteKeepsGuardDutyBehavior} see one
     * where it expects none. The flag keeps the teardown to the test that enabled it, so a
     * single-method run of this class still works.
     */
    @AfterEach
    void disableTheOrganizationAdminThisTestEnabled() {
        if (enabledOrganizationAdmin) {
            given().contentType("application/json")
                    .header("Authorization", auth("444444444444"))
                    .body("{\"adminAccountId\":\"444444444444\"}")
                    .post("/admin/disable")
                    .then().statusCode(200);
        }
    }

    @Test
    void createsUnassociatedMembersByDefault() {
        String detectorId = createDetector("111111111111");

        given().contentType("application/json")
                .header("Authorization", auth("111111111111"))
                .body("{\"accountDetails\":[{\"accountId\":\"222222222222\",\"email\":\"member@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("unprocessedAccounts", hasSize(0));

        given().header("Authorization", auth("111111111111"))
                .get("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].accountId", equalTo("222222222222"))
                .body("members[0].email", equalTo("member@example.com"))
                .body("members[0].relationshipStatus", equalTo("Created"));

        given().header("Authorization", auth("111111111111"))
                .queryParam("onlyAssociated", true)
                .get("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("members", hasSize(0));
    }

    @Test
    void delegatedAdministratorCreatesEnabledOrganizationMembers() {
        given().contentType("application/json")
                .header("Authorization", auth("444444444444"))
                .body("{\"adminAccountId\":\"444444444444\"}")
                .post("/admin/enable")
                .then().statusCode(200);
        enabledOrganizationAdmin = true;

        String detectorId = createDetector("444444444444");

        given().contentType("application/json")
                .header("Authorization", auth("444444444444"))
                .body("{\"accountDetails\":[{\"accountId\":\"555555555555\",\"email\":\"org-member@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(200);

        given().header("Authorization", auth("444444444444"))
                .queryParam("onlyAssociated", true)
                .get("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].relationshipStatus", equalTo("Enabled"));
    }

    @Test
    void rejectsInvalidMemberAccountId() {
        String detectorId = createDetector("333333333333");

        given().contentType("application/json")
                .header("Authorization", auth("333333333333"))
                .body("{\"accountDetails\":[{\"accountId\":\"bad\",\"email\":\"member@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void rejectedMixedBatchDoesNotPersistValidPrefix() {
        String detectorId = createDetector("666666666666");

        given().contentType("application/json")
                .header("Authorization", auth("666666666666"))
                .body("{\"accountDetails\":["
                        + "{\"accountId\":\"777777777777\",\"email\":\"valid@example.com\"},"
                        + "{\"accountId\":\"bad\",\"email\":\"invalid@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given().header("Authorization", auth("666666666666"))
                .get("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("members", hasSize(0));
    }

    private static String createDetector(String accountId) {
        return given()
                .contentType("application/json")
                .header("Authorization", auth(accountId))
                .body("{\"enable\":true}")
                .post("/detector")
                .then().statusCode(200)
                .extract().path("detectorId");
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/us-east-1/guardduty/aws4_request";
    }
}
