package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.OrganizationsRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Centralized root access end to end: the management account turns the feature on, a delegated
 * administrator sees the same organization-wide answer, and a privileged session acts as a member
 * account's root user scoped to the task policy it names.
 *
 * <p>Floci resolves a 12-digit access key id straight to an account id, so each request carries a
 * SigV4-shaped {@code Authorization} header naming the calling account. The accounts here are
 * their own, so this class never contends with another over one organization.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StsAssumeRootIntegrationTest {

    private static final String MANAGEMENT = "666666666661";
    private static final String OUTSIDER = "666666666669";
    private static final String UNRELATED_ACCOUNT = "666666666668";
    private static final String TASK_POLICY = "arn:aws:iam::aws:policy/root-task/S3UnlockBucketPolicy";

    private String organizationId;
    private String memberAccount;
    private String delegatedAdmin;
    private String managementKey;
    private String adminKey;
    private String outsiderKey;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * An IAM user of {@code accountId} holding one access key. AssumeRoot refuses the account root,
     * both the key that resolves to its ARN and the bare account-id key floci reads as that
     * principal, so every caller here is a user of the account rather than the account itself.
     */
    private static String accessKeyFor(String accountId, String userName) {
        iam(accountId, "CreateUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200);
        return iam(accountId, "CreateAccessKey").formParam("UserName", userName)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static RequestSpecification iam(String accountId, String action) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accountId, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
    }

    private static RequestSpecification assumeRoot(String accessKeyId, String targetPrincipal,
                                                   String taskPolicyArn) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "sts"))
                .formParam("Action", "AssumeRoot")
                .formParam("Version", "2011-06-15")
                .formParam("TargetPrincipal", targetPrincipal)
                .formParam("TaskPolicyArn.arn", taskPolicyArn);
    }

    @Test
    @Order(1)
    void anOrganizationWithAMemberAndADelegatedAdministrator() {
        organizationId = OrganizationsRequests.request(MANAGEMENT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Organization.Id");

        memberAccount = OrganizationsRequests.request(MANAGEMENT, "CreateAccount",
                "{\"Email\":\"member@example.com\",\"AccountName\":\"Member\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        delegatedAdmin = OrganizationsRequests.request(MANAGEMENT, "CreateAccount",
                "{\"Email\":\"iam-admin@example.com\",\"AccountName\":\"IamAdmin\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");

        OrganizationsRequests.request(MANAGEMENT, "EnableAWSServiceAccess",
                "{\"ServicePrincipal\":\"iam.amazonaws.com\"}")
                .when().post("/").then().statusCode(200);
        OrganizationsRequests.request(MANAGEMENT, "RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + delegatedAdmin + "\",\"ServicePrincipal\":\"iam.amazonaws.com\"}")
                .when().post("/").then().statusCode(200);

        managementKey = accessKeyFor(MANAGEMENT, "root-session-caller");
        adminKey = accessKeyFor(delegatedAdmin, "delegated-session-caller");
        outsiderKey = accessKeyFor(OUTSIDER, "outsider-session-caller");
    }

    /** "You have enabled centralized root access in your organization" is a prerequisite, not a default. */
    @Test
    @Order(2)
    void aPrivilegedSessionIsRefusedBeforeRootSessionsIsEnabled() {
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @Test
    @Order(3)
    void theManagementAccountEnablesRootSessions() {
        iam(MANAGEMENT, "EnableOrganizationsRootSessions")
                .when().post("/").then().statusCode(200)
                .body("EnableOrganizationsRootSessionsResponse.EnableOrganizationsRootSessionsResult"
                        + ".OrganizationId", equalTo(organizationId))
                .body("EnableOrganizationsRootSessionsResponse.EnableOrganizationsRootSessionsResult"
                        + ".EnabledFeatures.member", equalTo("RootSessions"));
    }

    /** The feature set is the organization's, so the delegated administrator reads what was enabled. */
    @Test
    @Order(4)
    void aDelegatedAdministratorSeesTheOrganizationsOwnFeatureSet() {
        iam(delegatedAdmin, "ListOrganizationsFeatures")
                .when().post("/").then().statusCode(200)
                .body("ListOrganizationsFeaturesResponse.ListOrganizationsFeaturesResult.OrganizationId",
                        equalTo(organizationId))
                .body("ListOrganizationsFeaturesResponse.ListOrganizationsFeaturesResult"
                        + ".EnabledFeatures.member", equalTo("RootSessions"));
    }

    @Test
    @Order(5)
    void onlyTheRightCallerMayMoveTheFeatures() {
        iam(delegatedAdmin, "EnableOrganizationsRootCredentialsManagement")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CallerIsNotManagementAccountException"));
        iam(memberAccount, "ListOrganizationsFeatures")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code",
                        equalTo("AccountNotManagementOrDelegatedAdministratorException"));
        iam(OUTSIDER, "ListOrganizationsFeatures")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OrganizationNotFoundException"));
    }

    @Test
    @Order(6)
    void aPrivilegedSessionActsAsTheMemberAccountRoot() {
        String accessKeyId = assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("DurationSeconds", "900")
                .when().post("/").then().statusCode(200)
                .body("AssumeRootResponse.AssumeRootResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .body("AssumeRootResponse.AssumeRootResult.Credentials.Expiration", notNullValue())
                .body("AssumeRootResponse.AssumeRootResult.SessionTokenSize", notNullValue())
                .extract().path("AssumeRootResponse.AssumeRootResult.Credentials.AccessKeyId");
        String sessionToken = assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .when().post("/").then().statusCode(200)
                .extract().path("AssumeRootResponse.AssumeRootResult.Credentials.SessionToken");

        given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "sts"))
                .header("X-Amz-Security-Token", sessionToken)
                .formParam("Action", "GetCallerIdentity")
                .formParam("Version", "2011-06-15")
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(memberAccount))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + memberAccount + ":root"));
    }

    @Test
    @Order(7)
    void aDelegatedAdministratorMayOpenOneToo() {
        assumeRoot(adminKey, memberAccount, TASK_POLICY)
                .when().post("/").then().statusCode(200)
                .body("AssumeRootResponse.AssumeRootResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    @Order(8)
    void theCallerTheTargetAndTheTaskPolicyAreAllChecked() {
        assumeRoot(outsiderKey, memberAccount, TASK_POLICY)
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        assumeRoot(managementKey, UNRELATED_ACCOUNT, TASK_POLICY)
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        // Privileged tasks are what the management account performs on members, not on itself.
        assumeRoot(managementKey, MANAGEMENT, TASK_POLICY)
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        assumeRoot(managementKey, memberAccount, "arn:aws:iam::aws:policy/AdministratorAccess")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("DurationSeconds", "901")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        given()
                .header("Authorization", EnforcementFixtures.auth(managementKey, "sts"))
                .formParam("Action", "AssumeRoot")
                .formParam("Version", "2011-06-15")
                .formParam("TaskPolicyArn.arn", TASK_POLICY)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /**
     * "The global endpoint is not supported for AssumeRoot. You must send this request to a
     * Regional AWS STS endpoint." Floci can only see that when the request names the global host,
     * which is why the refusal is on the host rather than on the signing region.
     */
    @Test
    @Order(9)
    void theGlobalEndpointIsRefusedWhenTheRequestArrivesOnIt() {
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .header("Host", "sts.amazonaws.com")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /** The five task policies the guide names all live under the one path the check reads. */
    @Test
    @Order(10)
    void everyTaskPolicyTheGuideNamesIsUsable() {
        for (String policy : new String[] {"IAMAuditRootUserCredentials", "IAMCreateRootUserPassword",
                "IAMDeleteRootUserCredentials", "S3UnlockBucketPolicy", "SQSUnlockQueuePolicy"}) {
            assumeRoot(managementKey, memberAccount, "arn:aws:iam::aws:policy/root-task/" + policy)
                    .when().post("/").then().statusCode(200)
                    .body("AssumeRootResponse.AssumeRootResult.Credentials.SessionToken", notNullValue());
        }
        iam(MANAGEMENT, "ListOrganizationsFeatures")
                .when().post("/").then().statusCode(200)
                .body("ListOrganizationsFeaturesResponse.ListOrganizationsFeaturesResult"
                        + ".EnabledFeatures.member", equalTo("RootSessions"));
    }

    /**
     * A customer-managed policy can carry the {@code /root-task/} path as easily as AWS's own, so
     * whose policy it is decides and not only where it sits: the model names five AWS managed
     * policies, and a document of the caller's own would scope the session to whatever it allows.
     */
    @Test
    @Order(11)
    void onlyAwsOwnRootTaskPoliciesCanScopeASession() {
        String ownPolicy = iam(MANAGEMENT, "CreatePolicy")
                .formParam("PolicyName", "LooksLikeATaskPolicy")
                .formParam("Path", "/root-task/")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\""
                        + ":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}")
                .when().post("/").then().statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        assumeRoot(managementKey, memberAccount, ownPolicy)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /**
     * {@code MinimumSessionTokenSize} is 0 to 4096 in the model, "The value must not exceed 4,096
     * bytes", and the size it asks for is the size issued, so it is held to that bound rather than
     * clamped to it.
     */
    @Test
    @Order(12)
    void theMinimumSessionTokenSizeIsHeldToTheBoundTheModelDeclares() {
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("MinimumSessionTokenSize", "4097")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("MinimumSessionTokenSize", "-1")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("MinimumSessionTokenSize", "two thousand")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        assumeRoot(managementKey, memberAccount, TASK_POLICY)
                .formParam("MinimumSessionTokenSize", "1024")
                .when().post("/").then().statusCode(200)
                .body("AssumeRootResponse.AssumeRootResult.SessionTokenSize", equalTo("1024"));
    }

    /**
     * "You cannot use root user credentials to call sts:AssumeRoot." The bare account-id key is
     * floci's own account-root principal, which {@code IamEnforcementFilter} says in as many words, so it
     * is refused here as the credential it stands for even though it resolves to no ARN.
     */
    @Test
    @Order(13)
    void theAccountRootCannotOpenAPrivilegedSession() {
        assumeRoot(MANAGEMENT, memberAccount, TASK_POLICY)
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"))
                .body("ErrorResponse.Error.Message", startsWith("Root user credentials cannot call"));
    }
}
