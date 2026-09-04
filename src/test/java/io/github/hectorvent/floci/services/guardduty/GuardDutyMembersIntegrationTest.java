package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class GuardDutyMembersIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createsAndListsMembers() {
        String detectorId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"enable\":true}")
                .post("/detector")
                .then().statusCode(200)
                .extract().path("detectorId");

        given().contentType("application/json")
                .header("Authorization", auth())
                .body("{\"accountDetails\":[{\"accountId\":\"222222222222\",\"email\":\"member@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("unprocessedAccounts", hasSize(0));

        given().header("Authorization", auth())
                .get("/detector/" + detectorId + "/member")
                .then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].accountId", equalTo("222222222222"))
                .body("members[0].email", equalTo("member@example.com"))
                .body("members[0].relationshipStatus", equalTo("Enabled"));
    }

    @Test
    void rejectsInvalidMemberAccountId() {
        String detectorId = given()
                .contentType("application/json")
                .header("Authorization", auth("333333333333"))
                .body("{\"enable\":true}")
                .post("/detector")
                .then().statusCode(200)
                .extract().path("detectorId");

        given().contentType("application/json")
                .header("Authorization", auth("333333333333"))
                .body("{\"accountDetails\":[{\"accountId\":\"bad\",\"email\":\"member@example.com\"}]}")
                .post("/detector/" + detectorId + "/member")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    private static String auth() {
        return auth("111111111111");
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/us-east-1/guardduty/aws4_request";
    }
}
