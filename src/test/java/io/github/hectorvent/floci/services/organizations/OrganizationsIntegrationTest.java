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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Single-account Organizations flow: the organization itself, the OU tree, accounts, policies,
 * tags, trusted access, delegation, the resource policy and effective policies.
 *
 * <p>Runs entirely as the default account, which becomes the management account. The final test
 * tears the organization down so the shared Quarkus instance is left with no organization for
 * {@code 000000000000} — {@link OrganizationsCrossAccountIntegrationTest} deliberately uses a
 * different management account so the two classes never contend for the same one.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";

    private static final String TAG_POLICY_CONTENT =
            "{\"tags\":{\"CostCenter\":{\"tag_key\":{\"@@assign\":\"CostCenter\"}}}}";
    private static final String SCP_CONTENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"ec2:*\",\"Resource\":\"*\"}]}";

    private String organizationId;
    private String rootId;
    private String ouId;
    private String nestedOuId;
    private String memberAccountId;
    private String createAccountRequestId;
    private String scpId;
    private String tagPolicyId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification organizations(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    // ──────────────────────────── Organization ────────────────────────────

    @Test
    @Order(1)
    void createOrganization() {
        organizationId = organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", matchesPattern("o-[a-z0-9]{10}"))
            .body("Organization.Arn", startsWith("arn:aws:organizations::000000000000:organization/o-"))
            .body("Organization.FeatureSet", equalTo("ALL"))
            .body("Organization.MasterAccountId", equalTo("000000000000"))
            .body("Organization.MasterAccountArn", startsWith("arn:aws:organizations::000000000000:account/o-"))
            .body("Organization.AvailablePolicyTypes.Type", hasItem("SERVICE_CONTROL_POLICY"))
            .extract().jsonPath().getString("Organization.Id");
    }

    @Test
    @Order(2)
    void createOrganizationTwiceFails() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyInOrganizationException"));
    }

    @Test
    @Order(3)
    void describeOrganization() {
        organizations("DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", equalTo(organizationId))
            .body("Organization.MasterAccountId", equalTo("000000000000"));
    }

    @Test
    @Order(4)
    void listRootsReturnsRootWithServiceControlPolicyEnabled() {
        rootId = organizations("ListRoots", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Roots", hasSize(1))
            .body("Roots[0].Id", matchesPattern("r-[a-z0-9]{4}"))
            .body("Roots[0].Name", equalTo("Root"))
            .body("Roots[0].Arn", startsWith("arn:aws:organizations::000000000000:root/" + organizationId))
            .body("Roots[0].PolicyTypes.Type", hasItem("SERVICE_CONTROL_POLICY"))
            .body("Roots[0].PolicyTypes.Status", hasItem("ENABLED"))
            .extract().jsonPath().getString("Roots[0].Id");
    }

    @Test
    @Order(5)
    void fullAwsAccessIsAttachedToTheRoot() {
        organizations("ListPoliciesForTarget",
                "{\"TargetId\":\"" + rootId + "\",\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policies.Id", hasItem("p-FullAWSAccess"))
            .body("Policies.find { it.Id == 'p-FullAWSAccess' }.AwsManaged", equalTo(true))
            .body("Policies.find { it.Id == 'p-FullAWSAccess' }.Name", equalTo("FullAWSAccess"));
    }

    // ──────────────────────────── Organizational units ────────────────────────────

    @Test
    @Order(10)
    void createOrganizationalUnit() {
        ouId = organizations("CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"Workloads\","
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnit.Id", matchesPattern("ou-[a-z0-9]{4}-[a-z0-9]{8}"))
            .body("OrganizationalUnit.Name", equalTo("Workloads"))
            .body("OrganizationalUnit.Arn", startsWith("arn:aws:organizations::000000000000:ou/" + organizationId))
            .extract().jsonPath().getString("OrganizationalUnit.Id");
    }

    @Test
    @Order(11)
    void createDuplicateOrganizationalUnitNameUnderSameParentFails() {
        organizations("CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"Workloads\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateOrganizationalUnitException"));
    }

    @Test
    @Order(12)
    void createNestedOrganizationalUnit() {
        nestedOuId = organizations("CreateOrganizationalUnit",
                "{\"ParentId\":\"" + ouId + "\",\"Name\":\"Production\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnit.Name", equalTo("Production"))
            .extract().jsonPath().getString("OrganizationalUnit.Id");
    }

    @Test
    @Order(13)
    void listOrganizationalUnitsForParent() {
        organizations("ListOrganizationalUnitsForParent", "{\"ParentId\":\"" + rootId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnits", hasSize(1))
            .body("OrganizationalUnits[0].Id", equalTo(ouId));
    }

    @Test
    @Order(14)
    void listChildrenReturnsNestedOu() {
        organizations("ListChildren",
                "{\"ParentId\":\"" + ouId + "\",\"ChildType\":\"ORGANIZATIONAL_UNIT\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Children", hasSize(1))
            .body("Children[0].Id", equalTo(nestedOuId))
            .body("Children[0].Type", equalTo("ORGANIZATIONAL_UNIT"));
    }

    @Test
    @Order(15)
    void listParentsWalksUpOneLevel() {
        organizations("ListParents", "{\"ChildId\":\"" + nestedOuId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parents", hasSize(1))
            .body("Parents[0].Id", equalTo(ouId))
            .body("Parents[0].Type", equalTo("ORGANIZATIONAL_UNIT"));
    }

    @Test
    @Order(16)
    void updateOrganizationalUnitRenames() {
        organizations("UpdateOrganizationalUnit",
                "{\"OrganizationalUnitId\":\"" + nestedOuId + "\",\"Name\":\"Prod\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnit.Name", equalTo("Prod"));

        organizations("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + nestedOuId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnit.Name", equalTo("Prod"));
    }

    @Test
    @Order(17)
    void describeUnknownOrganizationalUnitFails() {
        organizations("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"ou-abcd-99999999\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("OrganizationalUnitNotFoundException"));
    }

    // ──────────────────────────── Accounts ────────────────────────────

    @Test
    @Order(20)
    void createAccount() {
        var response = organizations("CreateAccount",
                "{\"Email\":\"dev@example.com\",\"AccountName\":\"Dev\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.Id", startsWith("car-"))
            .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
            .body("CreateAccountStatus.AccountName", equalTo("Dev"))
            .body("CreateAccountStatus.AccountId", matchesPattern("\\d{12}"))
            .body("CreateAccountStatus.RequestedTimestamp", notNullValue())
            .body("CreateAccountStatus.CompletedTimestamp", notNullValue())
            .extract().jsonPath();

        memberAccountId = response.getString("CreateAccountStatus.AccountId");
        createAccountRequestId = response.getString("CreateAccountStatus.Id");
    }

    @Test
    @Order(21)
    void createAccountWithDuplicateEmailReportsFailure() {
        organizations("CreateAccount", "{\"Email\":\"dev@example.com\",\"AccountName\":\"Dev2\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.State", equalTo("FAILED"))
            .body("CreateAccountStatus.FailureReason", equalTo("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @Order(22)
    void createAccountWithInvalidEmailFails() {
        organizations("CreateAccount", "{\"Email\":\"not-an-email\",\"AccountName\":\"Bad\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(23)
    void describeCreateAccountStatus() {
        organizations("DescribeCreateAccountStatus",
                "{\"CreateAccountRequestId\":\"" + createAccountRequestId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.AccountId", equalTo(memberAccountId));
    }

    @Test
    @Order(24)
    void listCreateAccountStatusFiltersByState() {
        organizations("ListCreateAccountStatus", "{\"States\":[\"SUCCEEDED\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatuses.AccountId", hasItem(memberAccountId))
            .body("CreateAccountStatuses.State", not(hasItem("FAILED")));
    }

    @Test
    @Order(25)
    void describeAccountAndListAccounts() {
        organizations("DescribeAccount", "{\"AccountId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Account.Id", equalTo(memberAccountId))
            .body("Account.Email", equalTo("dev@example.com"))
            .body("Account.Name", equalTo("Dev"))
            .body("Account.Status", equalTo("ACTIVE"))
            .body("Account.JoinedMethod", equalTo("CREATED"))
            .body("Account.Arn", startsWith("arn:aws:organizations::000000000000:account/" + organizationId));

        organizations("ListAccounts", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts.Id", hasItems("000000000000", memberAccountId));
    }

    @Test
    @Order(26)
    void moveAccountIntoOrganizationalUnit() {
        organizations("MoveAccount",
                "{\"AccountId\":\"" + memberAccountId + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListAccountsForParent", "{\"ParentId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts", hasSize(1))
            .body("Accounts[0].Id", equalTo(memberAccountId));
    }

    @Test
    @Order(27)
    void moveAccountFromWrongSourceParentFails() {
        organizations("MoveAccount",
                "{\"AccountId\":\"" + memberAccountId + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + nestedOuId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SourceParentNotMatchedException"));
    }

    @Test
    @Order(28)
    void deleteNonEmptyOrganizationalUnitFails() {
        organizations("DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("OrganizationalUnitNotEmptyException"));
    }

    @Test
    @Order(29)
    void removeManagementAccountFails() {
        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MasterCannotLeaveOrganizationException"));
    }

    // ──────────────────────────── Policies ────────────────────────────

    @Test
    @Order(30)
    void createServiceControlPolicy() {
        scpId = organizations("CreatePolicy",
                "{\"Name\":\"DenyEc2\",\"Description\":\"Deny EC2\",\"Type\":\"SERVICE_CONTROL_POLICY\","
                        + "\"Content\":" + quote(SCP_CONTENT) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy.PolicySummary.Id", matchesPattern("p-[a-z0-9]{8}"))
            .body("Policy.PolicySummary.Name", equalTo("DenyEc2"))
            .body("Policy.PolicySummary.Type", equalTo("SERVICE_CONTROL_POLICY"))
            .body("Policy.PolicySummary.AwsManaged", equalTo(false))
            .body("Policy.PolicySummary.Arn",
                    startsWith("arn:aws:organizations::000000000000:policy/" + organizationId
                            + "/service_control_policy/"))
            .body("Policy.Content", equalTo(SCP_CONTENT))
            .extract().jsonPath().getString("Policy.PolicySummary.Id");
    }

    @Test
    @Order(31)
    void createDuplicatePolicyNameFails() {
        organizations("CreatePolicy",
                "{\"Name\":\"DenyEc2\",\"Type\":\"SERVICE_CONTROL_POLICY\",\"Content\":" + quote(SCP_CONTENT) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicatePolicyException"));
    }

    @Test
    @Order(32)
    void attachAndListPolicy() {
        organizations("AttachPolicy", "{\"PolicyId\":\"" + scpId + "\",\"TargetId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListPoliciesForTarget",
                "{\"TargetId\":\"" + ouId + "\",\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policies.Id", hasItems("p-FullAWSAccess", scpId));

        organizations("ListTargetsForPolicy", "{\"PolicyId\":\"" + scpId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets", hasSize(1))
            .body("Targets[0].TargetId", equalTo(ouId))
            .body("Targets[0].Type", equalTo("ORGANIZATIONAL_UNIT"))
            .body("Targets[0].Name", equalTo("Workloads"));
    }

    @Test
    @Order(33)
    void attachSamePolicyTwiceFails() {
        organizations("AttachPolicy", "{\"PolicyId\":\"" + scpId + "\",\"TargetId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicatePolicyAttachmentException"));
    }

    @Test
    @Order(34)
    void deleteAttachedPolicyFails() {
        organizations("DeletePolicy", "{\"PolicyId\":\"" + scpId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("PolicyInUseException"));
    }

    @Test
    @Order(35)
    void updatePolicy() {
        organizations("UpdatePolicy",
                "{\"PolicyId\":\"" + scpId + "\",\"Description\":\"Deny EC2 everywhere\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy.PolicySummary.Description", equalTo("Deny EC2 everywhere"));
    }

    @Test
    @Order(36)
    void awsManagedPolicyCannotBeModified() {
        organizations("UpdatePolicy", "{\"PolicyId\":\"p-FullAWSAccess\",\"Description\":\"nope\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConstraintViolationException"));
    }

    @Test
    @Order(37)
    void detachingTheLastServiceControlPolicyIsRejected() {
        organizations("DetachPolicy", "{\"PolicyId\":\"" + scpId + "\",\"TargetId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DetachPolicy", "{\"PolicyId\":\"p-FullAWSAccess\",\"TargetId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConstraintViolationException"));
    }

    @Test
    @Order(38)
    void detachUnattachedPolicyFails() {
        organizations("DetachPolicy", "{\"PolicyId\":\"" + scpId + "\",\"TargetId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("PolicyNotAttachedException"));
    }

    // ──────────────────────────── Policy types and effective policies ────────────────────────────

    @Test
    @Order(40)
    void tagPolicyRequiresItsPolicyTypeEnabled() {
        organizations("CreatePolicy",
                "{\"Name\":\"CostCenter\",\"Type\":\"TAG_POLICY\",\"Content\":" + quote(TAG_POLICY_CONTENT) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("PolicyTypeNotEnabledException"));
    }

    @Test
    @Order(41)
    void enablePolicyType() {
        organizations("EnablePolicyType", "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Root.PolicyTypes.Type", hasItems("SERVICE_CONTROL_POLICY", "TAG_POLICY"));
    }

    @Test
    @Order(42)
    void enablePolicyTypeTwiceFails() {
        organizations("EnablePolicyType", "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("PolicyTypeAlreadyEnabledException"));
    }

    @Test
    @Order(43)
    void describeEffectivePolicyMergesTheInheritanceChain() {
        tagPolicyId = organizations("CreatePolicy",
                "{\"Name\":\"CostCenter\",\"Type\":\"TAG_POLICY\",\"Content\":" + quote(TAG_POLICY_CONTENT) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Policy.PolicySummary.Id");

        organizations("AttachPolicy", "{\"PolicyId\":\"" + tagPolicyId + "\",\"TargetId\":\"" + rootId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The account sits under the OU, which sits under the root, so a policy attached only to
        // the root must still reach it.
        organizations("DescribeEffectivePolicy",
                "{\"PolicyType\":\"TAG_POLICY\",\"TargetId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EffectivePolicy.PolicyType", equalTo("TAG_POLICY"))
            .body("EffectivePolicy.TargetId", equalTo(memberAccountId))
            .body("EffectivePolicy.LastUpdatedTimestamp", notNullValue())
            .body("EffectivePolicy.PolicyContent", equalTo(TAG_POLICY_CONTENT));
    }

    @Test
    @Order(44)
    void describeEffectivePolicyRejectsServiceControlPolicy() {
        organizations("DescribeEffectivePolicy",
                "{\"PolicyType\":\"SERVICE_CONTROL_POLICY\",\"TargetId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(45)
    void describeEffectivePolicyWithNoMatchingPolicyFails() {
        organizations("DescribeEffectivePolicy",
                "{\"PolicyType\":\"BACKUP_POLICY\",\"TargetId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EffectivePolicyNotFoundException"));
    }

    @Test
    @Order(46)
    void disablePolicyType() {
        organizations("DetachPolicy", "{\"PolicyId\":\"" + tagPolicyId + "\",\"TargetId\":\"" + rootId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeletePolicy", "{\"PolicyId\":\"" + tagPolicyId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DisablePolicyType", "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Root.PolicyTypes.Type", not(hasItem("TAG_POLICY")));
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Test
    @Order(50)
    void tagAndUntagResource() {
        organizations("ListTagsForResource", "{\"ResourceId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("test"));

        organizations("TagResource",
                "{\"ResourceId\":\"" + memberAccountId + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"platform\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListTagsForResource", "{\"ResourceId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("team"))
            .body("Tags[0].Value", equalTo("platform"));

        organizations("UntagResource",
                "{\"ResourceId\":\"" + memberAccountId + "\",\"TagKeys\":[\"team\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListTagsForResource", "{\"ResourceId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(51)
    void tagUnknownResourceFails() {
        organizations("ListTagsForResource", "{\"ResourceId\":\"not-a-resource\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("TargetNotFoundException"));
    }

    // ──────────────────────────── Trusted access and delegation ────────────────────────────

    @Test
    @Order(60)
    void enableAndListServiceAccess() {
        organizations("EnableAWSServiceAccess", "{\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListAWSServiceAccessForOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnabledServicePrincipals.ServicePrincipal", hasItem("config.amazonaws.com"))
            .body("EnabledServicePrincipals[0].DateEnabled", notNullValue());
    }

    @Test
    @Order(61)
    void registerAndListDelegatedAdministrator() {
        organizations("RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + memberAccountId + "\",\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListDelegatedAdministrators", "{\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DelegatedAdministrators", hasSize(1))
            .body("DelegatedAdministrators[0].Id", equalTo(memberAccountId))
            .body("DelegatedAdministrators[0].DelegationEnabledDate", notNullValue());

        organizations("ListDelegatedServicesForAccount", "{\"AccountId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DelegatedServices.ServicePrincipal", contains("config.amazonaws.com"));
    }

    @Test
    @Order(62)
    void registeringTheSameDelegatedAdministratorTwiceFails() {
        organizations("RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + memberAccountId + "\",\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AccountAlreadyRegisteredException"));
    }

    @Test
    @Order(63)
    void disablingServiceAccessWithADelegatedAdministratorFails() {
        organizations("DisableAWSServiceAccess", "{\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConstraintViolationException"));
    }

    @Test
    @Order(64)
    void deregisterDelegatedAdministratorThenDisableServiceAccess() {
        organizations("DeregisterDelegatedAdministrator",
                "{\"AccountId\":\"" + memberAccountId + "\",\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeregisterDelegatedAdministrator",
                "{\"AccountId\":\"" + memberAccountId + "\",\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AccountNotRegisteredException"));

        organizations("DisableAWSServiceAccess", "{\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("ListAWSServiceAccessForOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnabledServicePrincipals", hasSize(0));
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    @Test
    @Order(70)
    void resourcePolicyLifecycle() {
        organizations("DescribeResourcePolicy", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourcePolicyNotFoundException"));

        String content = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"organizations:Describe*\",\"Resource\":\"*\"}]}";

        organizations("PutResourcePolicy", "{\"Content\":" + quote(content) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourcePolicy.ResourcePolicySummary.Id", startsWith("rp-"))
            .body("ResourcePolicy.ResourcePolicySummary.Arn",
                    startsWith("arn:aws:organizations::000000000000:resourcepolicy/" + organizationId))
            .body("ResourcePolicy.Content", equalTo(content));

        organizations("DescribeResourcePolicy", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourcePolicy.Content", equalTo(content));

        organizations("DeleteResourcePolicy", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DescribeResourcePolicy", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourcePolicyNotFoundException"));
    }

    // ──────────────────────────── Unknown action ────────────────────────────

    @Test
    @Order(80)
    void unknownActionIsRejected() {
        organizations("NotARealAction", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    // ──────────────────────────── Teardown ────────────────────────────

    @Test
    @Order(90)
    void deleteOrganizationRequiresAnEmptyOrganization() {
        organizations("DeleteOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("OrganizationNotEmptyException"));
    }

    @Test
    @Order(91)
    void tearDownOrganization() {
        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeletePolicy", "{\"PolicyId\":\"" + scpId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + nestedOuId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeleteOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AWSOrganizationsNotInUseException"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
