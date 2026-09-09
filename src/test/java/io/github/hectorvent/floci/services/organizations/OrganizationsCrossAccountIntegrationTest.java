package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The invitation/handshake flow and member-account visibility — the behaviour that needs more
 * than one AWS account to exercise at all.
 *
 * <p>Floci resolves a 12-digit access key id straight to an account id, so each request here
 * carries a SigV4-shaped {@code Authorization} header whose credential names the calling account.
 * The management account is deliberately not the default {@code 000000000000} used by
 * {@link OrganizationsIntegrationTest}, so the two classes never contend for the same
 * organization inside one shared Quarkus instance.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsCrossAccountIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";

    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String INVITED_ACCOUNT = "333333333333";
    private static final String OTHER_ACCOUNT = "444444444444";

    private String organizationId;
    private String inviteHandshakeId;
    private String declinedHandshakeId;
    private String canceledHandshakeId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification organizations(String accountId, String action, String body) {
        return given()
                .header("Authorization", authorization(accountId))
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    private static String authorization(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260822/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    @Order(1)
    void createOrganizationAsManagementAccount() {
        organizationId = organizations(MANAGEMENT_ACCOUNT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.MasterAccountId", equalTo(MANAGEMENT_ACCOUNT))
            .extract().jsonPath().getString("Organization.Id");
    }

    @Test
    @Order(2)
    void anAccountOutsideTheOrganizationSeesNothing() {
        organizations(INVITED_ACCOUNT, "DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AWSOrganizationsNotInUseException"));
    }

    @Test
    @Order(3)
    void inviteAccountToOrganization() {
        inviteHandshakeId = organizations(MANAGEMENT_ACCOUNT, "InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"" + INVITED_ACCOUNT + "\",\"Type\":\"ACCOUNT\"},\"Notes\":\"join us\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.Id", notNullValue())
            .body("Handshake.State", equalTo("REQUESTED"))
            .body("Handshake.Action", equalTo("INVITE"))
            .body("Handshake.Arn",
                    org.hamcrest.Matchers.startsWith("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":handshake/"))
            .body("Handshake.RequestedTimestamp", notNullValue())
            .body("Handshake.ExpirationTimestamp", notNullValue())
            .body("Handshake.Parties.Id", hasItem(INVITED_ACCOUNT))
            .body("Handshake.Resources.Type", hasItem("ORGANIZATION"))
            .extract().jsonPath().getString("Handshake.Id");
    }

    @Test
    @Order(4)
    void handshakeResourcesUseOnlyTypesFromTheAwsEnum() {
        // HandshakeResourceType is a closed enum; a value outside it deserializes as
        // UNKNOWN_TO_SDK_VERSION in the SDK rather than failing loudly, so pin the exact types.
        organizations(MANAGEMENT_ACCOUNT, "DescribeHandshake", "{\"HandshakeId\":\"" + inviteHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.Resources.Type", org.hamcrest.Matchers.everyItem(
                    org.hamcrest.Matchers.in(List.of("ACCOUNT", "ORGANIZATION", "ORGANIZATION_FEATURE_SET",
                            "EMAIL", "MASTER_EMAIL", "MASTER_NAME", "NOTES", "PARENT_HANDSHAKE"))))
            .body("Handshake.Resources.find { it.Type == 'ORGANIZATION' }.Resources.Type",
                    org.hamcrest.Matchers.containsInAnyOrder("MASTER_EMAIL", "MASTER_NAME"));
    }

    @Test
    @Order(5)
    void duplicateInvitationIsRejectedWhileTheFirstIsOpen() {
        organizations(MANAGEMENT_ACCOUNT, "InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"" + INVITED_ACCOUNT + "\",\"Type\":\"ACCOUNT\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateHandshakeException"));
    }

    @Test
    @Order(6)
    void anotherAccountCannotAcceptSomeoneElsesInvitation() {
        organizations(OTHER_ACCOUNT, "AcceptHandshake", "{\"HandshakeId\":\"" + inviteHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    @Order(7)
    void theInviteeCanDescribeTheHandshake() {
        organizations(INVITED_ACCOUNT, "DescribeHandshake", "{\"HandshakeId\":\"" + inviteHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.Id", equalTo(inviteHandshakeId))
            .body("Handshake.State", equalTo("REQUESTED"));
    }

    @Test
    @Order(8)
    void acceptHandshakeJoinsTheOrganization() {
        organizations(INVITED_ACCOUNT, "AcceptHandshake", "{\"HandshakeId\":\"" + inviteHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.State", equalTo("ACCEPTED"));

        organizations(MANAGEMENT_ACCOUNT, "ListAccounts", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts.Id", hasItem(INVITED_ACCOUNT))
            .body("Accounts.find { it.Id == '" + INVITED_ACCOUNT + "' }.JoinedMethod", equalTo("INVITED"))
            .body("Accounts.find { it.Id == '" + INVITED_ACCOUNT + "' }.Status", equalTo("ACTIVE"));
    }

    @Test
    @Order(9)
    void aMemberAccountCanReadTheOrganization() {
        organizations(INVITED_ACCOUNT, "DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", equalTo(organizationId))
            .body("Organization.MasterAccountId", equalTo(MANAGEMENT_ACCOUNT));

        organizations(INVITED_ACCOUNT, "ListRoots", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Roots", hasSize(1));

        organizations(INVITED_ACCOUNT, "ListParents", "{\"ChildId\":\"" + INVITED_ACCOUNT + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parents[0].Type", equalTo("ROOT"));
    }

    @Test
    @Order(10)
    void aMemberAccountCannotMutateTheOrganization() {
        organizations(INVITED_ACCOUNT, "CreateOrganizationalUnit",
                "{\"ParentId\":\"r-0000\",\"Name\":\"Nope\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));

        organizations(INVITED_ACCOUNT, "CreateAccount",
                "{\"Email\":\"nope@example.com\",\"AccountName\":\"Nope\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    @Order(11)
    void invitingAnExistingMemberFails() {
        organizations(MANAGEMENT_ACCOUNT, "InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"" + INVITED_ACCOUNT + "\",\"Type\":\"ACCOUNT\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AccountAlreadyRegisteredException"));
    }

    @Test
    @Order(12)
    void declineHandshake() {
        declinedHandshakeId = organizations(MANAGEMENT_ACCOUNT, "InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"" + OTHER_ACCOUNT + "\",\"Type\":\"ACCOUNT\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Handshake.Id");

        organizations(OTHER_ACCOUNT, "DeclineHandshake", "{\"HandshakeId\":\"" + declinedHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.State", equalTo("DECLINED"));

        organizations(MANAGEMENT_ACCOUNT, "ListAccounts", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts.Id", not(hasItem(OTHER_ACCOUNT)));
    }

    @Test
    @Order(13)
    void cancelHandshake() {
        canceledHandshakeId = organizations(MANAGEMENT_ACCOUNT, "InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"" + OTHER_ACCOUNT + "\",\"Type\":\"ACCOUNT\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Handshake.Id");

        organizations(OTHER_ACCOUNT, "CancelHandshake", "{\"HandshakeId\":\"" + canceledHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));

        organizations(MANAGEMENT_ACCOUNT, "CancelHandshake", "{\"HandshakeId\":\"" + canceledHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.State", equalTo("CANCELED"));
    }

    @Test
    @Order(14)
    void actingOnAClosedHandshakeFails() {
        organizations(OTHER_ACCOUNT, "AcceptHandshake", "{\"HandshakeId\":\"" + canceledHandshakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidHandshakeTransitionException"));
    }

    @Test
    @Order(15)
    void listHandshakes() {
        organizations(MANAGEMENT_ACCOUNT, "ListHandshakesForOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshakes.Id",
                    org.hamcrest.Matchers.hasItems(inviteHandshakeId, declinedHandshakeId, canceledHandshakeId));

        organizations(MANAGEMENT_ACCOUNT, "ListHandshakesForOrganization",
                "{\"Filter\":{\"States\":[\"DECLINED\"]}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshakes", hasSize(1))
            .body("Handshakes[0].Id", equalTo(declinedHandshakeId));

        organizations(INVITED_ACCOUNT, "ListHandshakesForAccount", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshakes", hasSize(1))
            .body("Handshakes[0].Id", equalTo(inviteHandshakeId));
    }

    @Test
    @Order(16)
    void listHandshakesForOrganizationIsManagementAccountOnly() {
        organizations(INVITED_ACCOUNT, "ListHandshakesForOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    @Order(17)
    void theManagementAccountCannotLeave() {
        organizations(MANAGEMENT_ACCOUNT, "LeaveOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MasterCannotLeaveOrganizationException"));
    }

    @Test
    @Order(18)
    void aMemberAccountCanLeave() {
        organizations(INVITED_ACCOUNT, "LeaveOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations(INVITED_ACCOUNT, "DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AWSOrganizationsNotInUseException"));

        organizations(MANAGEMENT_ACCOUNT, "ListAccounts", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts.Id", not(hasItem(INVITED_ACCOUNT)));
    }

    @Test
    @Order(19)
    void tearDownOrganization() {
        organizations(MANAGEMENT_ACCOUNT, "DeleteOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
