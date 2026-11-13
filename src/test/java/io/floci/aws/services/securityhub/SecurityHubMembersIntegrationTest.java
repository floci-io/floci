package io.floci.aws.services.securityhub;

import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Handshake;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SecurityHubMembersIntegrationTest {

    @Inject
    OrganizationsService organizationsService;

    @Test
    void delegatedAdministratorManagesOrganizationMemberLifecycle() {
        String management = "947100000001";
        String administrator = "947100000002";
        String member = "947100000003";
        createOrganization(management, administrator, member);
        designateAdministrator(management, administrator);

        given().header("Authorization", auth(member)).get("/accounts").then().statusCode(404);
        createMembers(administrator, "[{\"AccountId\":\"" + member + "\"}]")
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));

        // An organization account is enabled for the member, with Security Hub's default standards.
        given().header("Authorization", auth(member)).get("/accounts").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/standards/get").then().statusCode(200)
                .body("StandardsSubscriptions", hasSize(2))
                .body("StandardsSubscriptions.StandardsSubscriptionArn",
                        everyItem(containsString(":" + member + ":subscription/")));

        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].AccountId", equalTo(member))
                .body("Members[0].AdministratorId", equalTo(administrator))
                .body("Members[0].MasterId", equalTo(administrator))
                .body("Members[0].MemberStatus", equalTo("Enabled"))
                .body("Members[0].InvitedAt", notNullValue())
                .body("Members[0].UpdatedAt", notNullValue())
                .body("Members[0].Email", nullValue())
                .body("NextToken", nullValue());

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"" + member + "\",\"947100000099\"]}")
                .post("/members/get").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].AccountId", equalTo(member))
                .body("UnprocessedAccounts", hasSize(1))
                .body("UnprocessedAccounts[0].AccountId", equalTo("947100000099"))
                .body("UnprocessedAccounts[0].ProcessingResult", notNullValue());

        // An organization account can only be disassociated, never deleted.
        deleteMembers(administrator, member).then().statusCode(200)
                .body("UnprocessedAccounts", hasSize(1))
                .body("UnprocessedAccounts[0].AccountId", equalTo(member));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"" + member + "\"]}")
                .post("/members/disassociate").then().statusCode(200);
        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members", hasSize(0));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].MemberStatus", equalTo("Removed"));

        createMembers(administrator, "[{\"AccountId\":\"" + member + "\"}]")
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members[0].MemberStatus", equalTo("Enabled"));
    }

    @Test
    void accountOutsideTheOrganizationIsCreatedAndCanBeDeleted() {
        String administrator = "947100000011";
        String invited = "957100000012";
        enableSecurityHub(administrator);

        createMembers(administrator, "[{\"AccountId\":\"" + invited + "\",\"Email\":\"sec@example.com\"}]")
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().header("Authorization", auth(invited)).get("/accounts").then().statusCode(404);
        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members", hasSize(0));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "FALSE")
                .get("/members").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].MemberStatus", equalTo("Created"))
                .body("Members[0].Email", equalTo("sec@example.com"))
                .body("Members[0].InvitedAt", nullValue());

        deleteMembers(administrator, invited).then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"" + invited + "\"]}")
                .post("/members/get").then().statusCode(200)
                .body("Members", hasSize(0))
                .body("UnprocessedAccounts[0].AccountId", equalTo(invited));
    }

    @Test
    void createMembersReportsAccountsItCannotAdd() {
        String management = "947100000021";
        String administrator = "947100000022";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);

        createMembers(administrator, "[{\"AccountId\":\"" + management + "\"},{\"AccountId\":\"" + administrator
                + "\"},{\"AccountId\":\"12345\"}]")
                .then().statusCode(200)
                .body("UnprocessedAccounts", hasSize(3))
                .body("UnprocessedAccounts.AccountId", equalTo(List.of(management, administrator, "12345")));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200).body("Members", hasSize(0));

        // Once the management account enables Security Hub itself, it can be added like any other member.
        enableSecurityHub(management);
        createMembers(administrator, "[{\"AccountId\":\"" + management + "\"}]")
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members[0].AccountId", equalTo(management))
                .body("Members[0].MemberStatus", equalTo("Enabled"));
    }

    @Test
    void memberOperationsValidateInputAndState() {
        String administrator = "947100000031";
        createMembers(administrator, "[{\"AccountId\":\"957100000031\"}]")
                .then().statusCode(401).body("__type", equalTo("InvalidAccessException"));
        given().header("Authorization", auth(administrator)).get("/members")
                .then().statusCode(401).body("__type", equalTo("InvalidAccessException"));

        enableSecurityHub(administrator);
        createMembers(administrator, "[]").then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        String fiftyOne = IntStream.range(0, 51)
                .mapToObj(i -> "{\"AccountId\":\"" + (957100001000L + i) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        createMembers(administrator, fiftyOne).then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        createMembers(administrator, "[{\"Email\":\"a@example.com\"}]")
                .then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        given().header("Authorization", auth(administrator)).queryParam("MaxResults", "51")
                .get("/members").then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "maybe")
                .get("/members").then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/members/get").then().statusCode(400).body("__type", equalTo("InvalidInputException"));

        // A disassociation naming a non-member fails as a whole: the real member stays as it was.
        createMembers(administrator, "[{\"AccountId\":\"957100000032\"},{\"AccountId\":\"957100000033\"}]")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"957100000032\",\"957100000099\"]}")
                .post("/members/disassociate").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"957100000032\"]}")
                .post("/members/get").then().statusCode(200)
                .body("Members[0].MemberStatus", equalTo("Created"));

        String nextToken = given().header("Authorization", auth(administrator))
                .queryParam("OnlyAssociated", "false").queryParam("MaxResults", "1")
                .get("/members").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].AccountId", equalTo("957100000032"))
                .extract().path("NextToken");
        given().header("Authorization", auth(administrator))
                .queryParam("OnlyAssociated", "false").queryParam("MaxResults", "1").queryParam("NextToken", nextToken)
                .get("/members").then().statusCode(200)
                .body("Members[0].AccountId", equalTo("957100000033"))
                .body("NextToken", nullValue());
    }

    @Test
    void disablingTheAdministratorRemovesItsOrganizationRole() {
        String management = "947100000041";
        String administrator = "947100000042";
        String member = "947100000043";
        String successor = "947100000044";
        createOrganization(management, administrator, member, successor);
        designateAdministrator(management, administrator);
        createMembers(administrator, "[{\"AccountId\":\"" + member + "\"},{\"AccountId\":\"957100000041\"}]")
                .then().statusCode(200);

        disableAdministrator(administrator, administrator).then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        disableAdministrator(management, successor).then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"AdminAccountId\":\"" + administrator + "\",\"Feature\":\"SecurityHubV2\"}")
                .post("/organization/admin/disable").then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        disableAdministrator(management, administrator).then().statusCode(200);
        given().header("Authorization", auth(management)).get("/organization/admin").then().statusCode(200)
                .body("AdminAccounts", hasSize(0));
        given().header("Authorization", auth(administrator)).get("/accounts").then().statusCode(200);
        given().header("Authorization", auth(administrator)).get("/organization/configuration")
                .then().statusCode(401).body("__type", equalTo("InvalidAccessException"));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200)
                .body("Members.find { it.AccountId == '" + member + "' }.MemberStatus", equalTo("Removed"))
                .body("Members.find { it.AccountId == '957100000041' }.MemberStatus", equalTo("Created"));
        disableAdministrator(management, administrator).then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        designateAdministrator(management, successor);
        given().header("Authorization", auth(management)).get("/organization/admin").then().statusCode(200)
                .body("AdminAccounts[0].AccountId", equalTo(successor));
    }

    @Test
    void managementAccountCanRemoveItselfAsAdministrator() {
        String management = "947100000051";
        String member = "947100000052";
        createOrganization(management, member);
        enableSecurityHub(management);
        designateAdministrator(management, management);
        createMembers(management, "[{\"AccountId\":\"" + member + "\"}]").then().statusCode(200);

        disableAdministrator(management, management).then().statusCode(200);
        given().header("Authorization", auth(management)).get("/organization/admin").then().statusCode(200)
                .body("AdminAccounts", hasSize(0));
        given().header("Authorization", auth(management)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200)
                .body("Members[0].MemberStatus", equalTo("Removed"));
    }

    @Test
    void sharedMembersRoutesFollowTheSigningService() {
        String administrator = "947100000061";
        enableSecurityHub(administrator);
        createMembers(administrator, "[{\"AccountId\":\"957100000061\"}]").then().statusCode(200);

        given().contentType("application/json").header("Authorization", EnforcementFixtures.auth(administrator, "macie2"))
                .body("{}").post("/macie").then().statusCode(200);
        given().header("Authorization", EnforcementFixtures.auth(administrator, "macie2")).get("/members").then().statusCode(200)
                .body("members", hasSize(0));
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200)
                .body("Members", hasSize(1));
        given().contentType("application/json").header("Authorization", EnforcementFixtures.auth(administrator, "guardduty"))
                .body("{}").post("/members").then().statusCode(400)
                .body("__type", equalTo("UnknownOperationException"));
        given().header("Authorization", EnforcementFixtures.auth(administrator, "guardduty")).get("/members").then().statusCode(400)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void rejectedBatchChangesNothing() {
        String management = "947100000071";
        String administrator = "947100000072";
        String member = "947100000073";
        createOrganization(management, administrator, member);
        designateAdministrator(management, administrator);

        // The valid organization account comes first, so a half-applied batch would have enabled it.
        createMembers(administrator, "[{\"AccountId\":\"" + member + "\"},{\"AccountId\":\"957100000071\","
                + "\"Email\":\" \"}]")
                .then().statusCode(400).body("__type", equalTo("InvalidInputException"));
        given().header("Authorization", auth(member)).get("/accounts").then().statusCode(404);
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200).body("Members", hasSize(0));
    }

    @Test
    void createdMemberBecomesEnabledOnceItJoinsTheOrganization() {
        String management = "947100000081";
        String administrator = "947100000082";
        String latecomer = "957100000083";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);

        createMembers(administrator, "[{\"AccountId\":\"" + latecomer + "\"}]").then().statusCode(200);
        given().header("Authorization", auth(administrator)).queryParam("OnlyAssociated", "false")
                .get("/members").then().statusCode(200).body("Members[0].MemberStatus", equalTo("Created"));

        joinOrganization(management, latecomer);
        createMembers(administrator, "[{\"AccountId\":\"" + latecomer + "\"}]")
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().header("Authorization", auth(administrator)).get("/members").then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].MemberStatus", equalTo("Enabled"))
                .body("Members[0].InvitedAt", notNullValue());
        given().header("Authorization", auth(latecomer)).get("/accounts").then().statusCode(200);
    }

    @Test
    void presignedMemberRequestsUseTheCredentialScope() {
        String administrator = "947100000091";
        enableSecurityHub(administrator);

        // presignedQuery is already URL-encoded, so RestAssured must send it as it is.
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"AccountDetails\":[{\"AccountId\":\"957100000091\"}]}")
                .post("/members?" + EnforcementFixtures.presignedQuery(administrator, "securityhub"))
                .then().statusCode(200).body("UnprocessedAccounts", hasSize(0));
        given().urlEncodingEnabled(false).get("/members?OnlyAssociated=false&" + EnforcementFixtures.presignedQuery(administrator, "securityhub"))
                .then().statusCode(200)
                .body("Members", hasSize(1))
                .body("Members[0].AccountId", equalTo("957100000091"));
    }

    private Response createMembers(String administrator, String accountDetails) {
        return given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountDetails\":" + accountDetails + "}").post("/members");
    }

    private Response deleteMembers(String administrator, String accountId) {
        return given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AccountIds\":[\"" + accountId + "\"]}").post("/members/delete");
    }

    private Response disableAdministrator(String caller, String adminAccountId) {
        return given().contentType("application/json").header("Authorization", auth(caller))
                .body("{\"AdminAccountId\":\"" + adminAccountId + "\"}").post("/organization/admin/disable");
    }

    private void enableSecurityHub(String accountId) {
        given().contentType("application/json").header("Authorization", auth(accountId))
                .body("{}").post("/accounts").then().statusCode(200);
    }

    private void createOrganization(String managementAccountId, String... members) {
        organizationsService.createOrganization(managementAccountId, "ALL");
        for (String member : members) {
            joinOrganization(managementAccountId, member);
        }
    }

    private void joinOrganization(String managementAccountId, String member) {
        Handshake handshake = organizationsService.inviteAccountToOrganization(
                managementAccountId, member, "ACCOUNT", null, null);
        organizationsService.acceptHandshake(member, handshake.getId());
    }

    private void designateAdministrator(String managementAccountId, String administratorAccountId) {
        given().contentType("application/json").header("Authorization", auth(managementAccountId))
                .body("{\"AdminAccountId\":\"" + administratorAccountId + "\"}")
                .post("/organization/admin/enable").then().statusCode(200);
    }

    private static String auth(String accountId) {
        return EnforcementFixtures.auth(accountId, "securityhub");
    }
}
