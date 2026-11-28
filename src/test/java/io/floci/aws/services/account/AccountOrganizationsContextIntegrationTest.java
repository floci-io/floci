package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.OrganizationsRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.floci.aws.services.account.AccountNameRequests.accountNameRequest;
import static io.floci.aws.services.account.AccountRequests.account;
import static io.floci.aws.services.account.AlternateContactRequests.alternateContactRequest;
import static io.floci.aws.services.account.ContactInformationRequests.contactInformationRequest;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/** Requests that name an {@code AccountId}, sent by the accounts of one organization. */
@QuarkusTest
class AccountOrganizationsContextIntegrationTest {
    /** The management account of an organization of this class's own, which no other test uses. */
    private static final String MANAGEMENT = "731000826800";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        refused(account(delegatedAdministrator, "/putAccountName",
                accountNameRequest(MANAGEMENT, "Management").toString()));
        refused(account(delegatedAdministrator, "/getAccountInformation", "{\"AccountId\":\"" + MANAGEMENT + "\"}"));
    }

    @Test
    void managementAccountCannotNameItself() {
        refused(account(MANAGEMENT, "/putAlternateContact", alternateContact(MANAGEMENT)));
    }

    @Test
    void memberAccountIsDescribedByItsOrganizationRecord() throws Exception {
        JsonNode described = describeAccount(member);

        account(delegatedAdministrator, "/getAccountInformation", "{\"AccountId\":\"" + member + "\"}")
                .statusCode(200)
                .body("AccountId", equalTo(member))
                .body("AccountName", equalTo("account-member"))
                .body("AccountCreatedDate",
                        equalTo(Instant.ofEpochSecond(described.path("JoinedTimestamp").longValue()).toString()))
                .body("AccountState", equalTo("ACTIVE"));
    }

    @Test
    void managementAccountHasNoCreationDate() throws Exception {
        account(MANAGEMENT, "/getAccountInformation", "{}").statusCode(200)
                .body("AccountId", equalTo(MANAGEMENT))
                .body("AccountName", equalTo(describeAccount(MANAGEMENT).path("Name").textValue()))
                .body("AccountState", equalTo("ACTIVE"))
                .body("$", not(hasKey("AccountCreatedDate")));
    }

    @Test
    void renamingAMemberAccountShowsInDescribeAccount() {
        String renamed = createAccount("account-to-rename");

        account(MANAGEMENT, "/putAccountName", accountNameRequest(renamed, "Renamed Member").toString())
                .statusCode(200).body(equalTo(""));

        organizations("DescribeAccount", "{\"AccountId\":\"" + renamed + "\"}")
                .body("Account.Name", equalTo("Renamed Member"));
        organizations("ListAccounts", "{}")
                .body("Accounts.find { it.Id == '" + renamed + "' }.Name", equalTo("Renamed Member"));
        account(renamed, "/getAccountInformation", "{}").statusCode(200)
                .body("AccountName", equalTo("Renamed Member"));
    }

    @Test
    void nameOutlivesTheOrganization() {
        String leaving = createAccount("account-to-remove");
        account(delegatedAdministrator, "/putAccountName", accountNameRequest(leaving, "Former Member").toString())
                .statusCode(200);

        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + leaving + "\"}");

        account(leaving, "/getAccountInformation", "{}").statusCode(200)
                .body("AccountName", equalTo("Former Member"))
                .body("AccountState", equalTo("ACTIVE"))
                .body("$", not(hasKey("AccountCreatedDate")));
    }

    @Test
    void removedAccountKeepsTheNameItWasCreatedWith() {
        String removed = createAccount("account-removed-unnamed");

        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + removed + "\"}");

        account(removed, "/getAccountInformation", "{}").statusCode(200)
                .body("AccountName", equalTo("account-removed-unnamed"));
    }

    @Test
    void accountThatLeavesKeepsItsName() {
        String leaving = createAccount("account-that-leaves");

        OrganizationsRequests.request(leaving, "LeaveOrganization", "{}").when().post("/").then().statusCode(200);

        account(leaving, "/getAccountInformation", "{}").statusCode(200)
                .body("AccountName", equalTo("account-that-leaves"));
    }

    @Test
    void managementAccountRenamesItselfWithoutAnAccountId() {
        account(MANAGEMENT, "/putAccountName", accountNameRequest("Management Account").toString())
                .statusCode(200);

        organizations("DescribeAccount", "{\"AccountId\":\"" + MANAGEMENT + "\"}")
                .body("Account.Name", equalTo("Management Account"));
    }

    @Test
    void closedAccountReportsClosed() {
        String closing = createAccount("account-to-close");
        organizations("CloseAccount", "{\"AccountId\":\"" + closing + "\"}");

        account(MANAGEMENT, "/getAccountInformation", "{\"AccountId\":\"" + closing + "\"}")
                .statusCode(200).body("AccountState", equalTo("CLOSED"));
    }

    private static JsonNode describeAccount(String accountId) throws Exception {
        String body = organizations("DescribeAccount", "{\"AccountId\":\"" + accountId + "\"}").extract().asString();
        return MAPPER.readTree(body).path("Account");
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
}
