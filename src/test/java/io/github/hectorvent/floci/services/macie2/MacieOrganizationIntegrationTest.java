package io.github.hectorvent.floci.services.macie2;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class MacieOrganizationIntegrationTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";
    private static final String GUARDDUTY_ACCOUNT = "333333333333";

    @Inject
    MacieService macieService;

    @BeforeEach
    void resetMacieState() {
        macieService.clear();
    }

    @Test
    void delegatedAdministratorOwnsOrganizationConfiguration() {
        given().header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .get("/admin").then().statusCode(200).body("adminAccounts", hasSize(0));

        given().contentType("application/json").header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .body("{\"adminAccountId\":\"" + ADMIN_ACCOUNT + "\"}")
                .post("/admin").then().statusCode(200);

        given().header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .get("/admin").then().statusCode(200)
                .body("adminAccounts[0].accountId", equalTo(ADMIN_ACCOUNT));

        given().header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .get("/macie").then().statusCode(200).body("status", equalTo("ENABLED"));

        given().contentType("application/json").header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .body("{\"autoEnable\":true}")
                .patch("/admin/configuration").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given().contentType("application/json").header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .body("{\"autoEnable\":true}")
                .patch("/admin/configuration").then().statusCode(200);
        given().header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .get("/admin/configuration").then().statusCode(200).body("autoEnable", equalTo(true));
    }


    @Test
    void delegatedAdministratorCreatesAndListsOrganizationMember() {
        given().contentType("application/json").header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .body("{\"adminAccountId\":\"" + ADMIN_ACCOUNT + "\"}")
                .post("/admin").then().statusCode(200);

        given().contentType("application/json").header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .body("{\"account\":{\"accountId\":\"444444444444\","
                        + "\"email\":\"member@example.com\"},\"tags\":{\"team\":\"security\"}}")
                .post("/members").then().statusCode(200)
                .body("arn", equalTo(
                        "arn:aws:macie2:us-east-1:" + ADMIN_ACCOUNT + ":member/444444444444"));

        given().header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .get("/members").then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].accountId", equalTo("444444444444"))
                .body("members[0].administratorAccountId", equalTo(ADMIN_ACCOUNT))
                .body("members[0].masterAccountId", equalTo(ADMIN_ACCOUNT))
                .body("members[0].email", nullValue())
                .body("members[0].relationshipStatus", equalTo("Enabled"))
                .body("members[0].tags.team", equalTo("security"));
    }

    @Test
    void listMembersRejectsInvalidOnlyAssociatedValue() {
        given().contentType("application/json").header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .body("{\"adminAccountId\":\"" + ADMIN_ACCOUNT + "\"}")
                .post("/admin").then().statusCode(200);

        given().header("Authorization", auth(ADMIN_ACCOUNT, "macie2"))
                .queryParam("onlyAssociated", "maybe")
                .get("/members").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void sharedAdminRouteKeepsGuardDutyBehavior() {
        given().header("Authorization", auth(GUARDDUTY_ACCOUNT, "guardduty"))
                .get("/admin").then().statusCode(200).body("adminAccounts", hasSize(0));
    }

    @Test
    void presignedMacieAdminRequestUsesCredentialScope() {
        given().contentType("application/json").header("Authorization", auth(MANAGEMENT_ACCOUNT, "macie2"))
                .body("{\"adminAccountId\":\"" + ADMIN_ACCOUNT + "\"}")
                .post("/admin").then().statusCode(200);

        given().queryParam("X-Amz-Credential",
                        MANAGEMENT_ACCOUNT + "/20260101/us-east-1/macie2/aws4_request")
                .get("/admin").then().statusCode(200)
                .body("adminAccounts[0].accountId", equalTo(ADMIN_ACCOUNT));
    }

    private static String auth(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260101/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
