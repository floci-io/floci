package io.floci.aws.services.account;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.OrganizationsRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.account.AlternateContactRequests.alternateContactRequest;
import static io.floci.aws.services.account.ContactInformationRequests.contactInformationRequest;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** Requests that name an {@code AccountId}, sent by the accounts of one organization. */
@QuarkusTest
class AccountOrganizationsContextIntegrationTest {
    /** The management account of an organization of this class's own, which no other test uses. */
    private static final String MANAGEMENT = "731000826800";

    private static String delegatedAdministrator;
    private static String member;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Once for the class, from a test's own setup: the port is not known before the first test. */
    @BeforeEach
    void createOrganizationWithADelegatedAdministrator() {
        if (delegatedAdministrator != null) {
            return;
        }
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}");
        member = createAccount("account-member");
        String administrator = createAccount("account-delegated-admin");
        organizations("EnableAWSServiceAccess", "{\"ServicePrincipal\":\"account.amazonaws.com\"}");
        organizations("RegisterDelegatedAdministrator", "{\"AccountId\":\"" + administrator
                + "\",\"ServicePrincipal\":\"account.amazonaws.com\"}");
        delegatedAdministrator = administrator;
    }

    @Test
    void delegatedAdministratorReachesAMemberAccount() {
        account(delegatedAdministrator, "/putAlternateContact", alternateContact(member)).statusCode(200);

        account(member, "/getAlternateContact", "{\"AlternateContactType\":\"SECURITY\"}")
                .statusCode(200).body("AlternateContact.EmailAddress", equalTo("security@example.com"));
    }

    @Test
    void delegatedAdministratorCannotNameTheManagementAccount() {
        String target = "{\"AccountId\":\"" + MANAGEMENT + "\",\"AlternateContactType\":\"SECURITY\"}";

        refused(account(delegatedAdministrator, "/putAlternateContact", alternateContact(MANAGEMENT)));
        refused(account(delegatedAdministrator, "/getAlternateContact", target));
        refused(account(delegatedAdministrator, "/deleteAlternateContact", target));
        refused(account(delegatedAdministrator, "/putContactInformation",
                contactInformationRequest().put("AccountId", MANAGEMENT).toString()));
        refused(account(delegatedAdministrator, "/getContactInformation", "{\"AccountId\":\"" + MANAGEMENT + "\"}"));
    }

    @Test
    void managementAccountCannotNameItself() {
        refused(account(MANAGEMENT, "/putAlternateContact", alternateContact(MANAGEMENT)));
    }

    private static void refused(ValidatableResponse response) {
        response.statusCode(403).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("management account of the organization"));
    }

    private static String alternateContact(String accountId) {
        return alternateContactRequest().put("AccountId", accountId).toString();
    }

    private static String createAccount(String name) {
        return organizations("CreateAccount",
                "{\"Email\":\"" + name + "@example.com\",\"AccountName\":\"" + name + "\"}")
                .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
                .extract().path("CreateAccountStatus.AccountId");
    }

    private static ValidatableResponse organizations(String action, String body) {
        return OrganizationsRequests.request(MANAGEMENT, action, body).when().post("/").then().statusCode(200);
    }

    private static ValidatableResponse account(String caller, String path, String body) {
        return given().contentType("application/json")
                .header("Authorization", EnforcementFixtures.auth(caller, "account"))
                .body(body).post(path).then();
    }
}
