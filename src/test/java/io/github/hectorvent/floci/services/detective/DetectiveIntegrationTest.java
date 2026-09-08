package io.github.hectorvent.floci.services.detective;

import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DetectiveIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/detective/aws4_request";

    @Inject
    OrganizationsService organizationsService;

    private boolean createdOrganization;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * Organizations state is shared by every test in the JVM and keyed by the management account,
     * so an organization left behind here makes the next class that creates one under
     * {@code 000000000000} (e.g. {@code OrganizationsIntegrationTest}) fail with
     * AlreadyInOrganizationException.
     */
    @AfterEach
    void deleteTheOrganizationThisTestCreated() {
        if (createdOrganization) {
            organizationsService.deleteOrganization("000000000000");
        }
    }

    @Test
    void organizationGraphAndMemberLifecycleMatchesAwsContract() {
        organizationsService.createOrganization("000000000000", "ALL");
        createdOrganization = true;
        String enableBody = post("/orgs/enableAdminAccount", "{\"AccountId\":\"000000000000\"}")
                .statusCode(200).extract().asString();
        assertTrue(enableBody.isEmpty());

        String graphArn = post("/graphs/list", "{\"MaxResults\":200}")
                .statusCode(200)
                .body("GraphList", hasSize(1))
                .extract().path("GraphList[0].Arn");

        String updateBody = post("/orgs/updateOrganizationConfiguration",
                "{\"GraphArn\":\"" + graphArn + "\",\"AutoEnable\":true}")
                .statusCode(200).extract().asString();
        assertTrue(updateBody.isEmpty());
        post("/orgs/updateOrganizationConfiguration", "{\"GraphArn\":\"" + graphArn + "\"}")
                .statusCode(200);
        post("/orgs/describeOrganizationConfiguration", "{\"GraphArn\":\"" + graphArn + "\"}")
                .statusCode(200).body("AutoEnable", equalTo(true));

        post("/graph/members", "{\"GraphArn\":\"" + graphArn
                + "\",\"Accounts\":[{\"AccountId\":\"444444444444\"},{\"AccountId\":\"bad\"}]}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
        post("/graph/members/list", "{\"GraphArn\":\"" + graphArn + "\"}")
                .statusCode(200).body("MemberDetails", hasSize(0));

        String create = "{\"GraphArn\":\"" + graphArn
                + "\",\"Accounts\":[{\"AccountId\":\"111111111111\"}]}";
        post("/graph/members", create)
                .statusCode(200)
                .body("Members[0].Status", equalTo("ACCEPTED_BUT_DISABLED"))
                .body("UnprocessedAccounts", hasSize(0));
        post("/graph/members", create)
                .statusCode(200)
                .body("Members", hasSize(0))
                .body("UnprocessedAccounts[0].AccountId", equalTo("111111111111"));

        String monitoringBody = post("/graph/member/monitoringstate",
                "{\"GraphArn\":\"" + graphArn + "\",\"AccountId\":\"111111111111\"}")
                .statusCode(200).extract().asString();
        assertTrue(monitoringBody.isEmpty());
        post("/graph/members/list", "{\"GraphArn\":\"" + graphArn + "\",\"MaxResults\":200}")
                .statusCode(200).body("MemberDetails[0].Status", equalTo("ENABLED"));
    }

    @Test
    void invalidAdminAccountIdReturnsValidationError() {
        post("/orgs/enableAdminAccount", "{\"AccountId\":\"bad\"}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    private static io.restassured.response.ValidatableResponse post(String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body(body)
                .post(path)
                .then();
    }
}
