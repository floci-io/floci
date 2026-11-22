package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Which dependency a blocked delete names, and in the sentence AWS was recorded answering. Three
 * sentences cover the four refusals: an instance profile still holding the role, a managed policy
 * that has to be detached, and an inline policy that has to be deleted. Each is entirely fixed
 * text, so a caller reading it to pick its next step gets the dependency that is actually there.
 *
 * <p>{@code docs/services/iam.md} carries the recordings and says which part of the ordering is a
 * reading rather than one of them.
 */
@QuarkusTest
class IamDeleteConflictDependencyIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261122/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String MUST_DETACH_POLICIES =
            "Cannot delete entity, must detach all policies first.";
    private static final String MUST_DELETE_POLICIES =
            "Cannot delete entity, must delete policies first.";
    private static final String MUST_REMOVE_PROFILE_ROLES =
            "Cannot delete entity, must remove roles from instance profile first.";

    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static final String PERMISSION_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /**
     * What a teardown step accepts. A test here may clear a dependency itself, to show the delete
     * going through once it is gone, so a resource that is already absent is the step's work
     * already done rather than a leak. Anything else, a refusal above all, still reports.
     */
    private static void cleared(RequestSpecification request) {
        request.when().post("/").then().statusCode(anyOf(is(200), is(404)));
    }

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private String createRole() {
        String roleName = unique("conflict-role");
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam("DeleteRole").formParam("RoleName", roleName)));
        return roleName;
    }

    private String createGroup() {
        String groupName = unique("conflict-group");
        iam("CreateGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam("DeleteGroup").formParam("GroupName", groupName)));
        return groupName;
    }

    private String createUser() {
        String userName = unique("conflict-user");
        iam("CreateUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam("DeleteUser").formParam("UserName", userName)));
        return userName;
    }

    private String pairedProfile(String roleName) {
        String profileName = unique("conflict-profile");
        iam("CreateInstanceProfile").formParam("InstanceProfileName", profileName)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam("DeleteInstanceProfile")
                .formParam("InstanceProfileName", profileName)));
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam("RemoveRoleFromInstanceProfile")
                .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)));
        return profileName;
    }

    private String managedPolicy() {
        String arn = iam("CreatePolicy").formParam("PolicyName", unique("conflict-policy"))
                .formParam("PolicyDocument", PERMISSION_POLICY)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> cleared(iam("DeletePolicy").formParam("PolicyArn", arn)));
        return arn;
    }

    private String attachToRole(String roleName) {
        return attach("AttachRolePolicy", "DetachRolePolicy", "RoleName", roleName);
    }

    private String attachToGroup(String groupName) {
        return attach("AttachGroupPolicy", "DetachGroupPolicy", "GroupName", groupName);
    }

    /** Attaches a managed policy to the entity and hands back its ARN. */
    private String attach(String action, String undo, String param, String entityName) {
        String arn = managedPolicy();
        iam(action).formParam(param, entityName).formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(
                iam(undo).formParam(param, entityName).formParam("PolicyArn", arn)));
        return arn;
    }

    private String inlineOnRole(String roleName) {
        return putInline("PutRolePolicy", "DeleteRolePolicy", "RoleName", roleName);
    }

    /** Embeds an inline policy in the entity and hands back its name. */
    private String putInline(String action, String undo, String param, String entityName) {
        String policyName = unique("conflict-inline");
        iam(action).formParam(param, entityName).formParam("PolicyName", policyName)
                .formParam("PolicyDocument", PERMISSION_POLICY)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> cleared(iam(undo).formParam(param, entityName)
                .formParam("PolicyName", policyName)));
        return policyName;
    }

    /** The role an instance profile holds: the caller is told to take it out of the profile. */
    @Test
    void aRoleAnInstanceProfileHoldsNamesTheProfile() {
        String roleName = createRole();
        pairedProfile(roleName);
        iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_REMOVE_PROFILE_ROLES));
    }

    /** The profile holding it is refused with the same sentence, which is AWS's for both ends. */
    @Test
    void theProfileHoldingARoleCarriesTheSameSentence() {
        String profileName = pairedProfile(createRole());
        iam("DeleteInstanceProfile").formParam("InstanceProfileName", profileName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_REMOVE_PROFILE_ROLES));
    }

    /**
     * Both dependencies at once: the profile is reported, which is the precedence AWS was recorded
     * answering and the one a cleanup loop needs, since detaching the policy changes nothing here.
     */
    @Test
    void theProfileIsNamedAheadOfTheAttachedPolicy() {
        String roleName = createRole();
        pairedProfile(roleName);
        attachToRole(roleName);
        iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(409)
                .body(MESSAGE, equalTo(MUST_REMOVE_PROFILE_ROLES));
    }

    /** A managed policy is detached, and the sentence says so. */
    @Test
    void aRoleCarryingAManagedPolicyAsksForADetach() {
        String roleName = createRole();
        attachToRole(roleName);
        iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_DETACH_POLICIES));
    }

    /** An inline policy is deleted rather than detached, and the sentence says that instead. */
    @Test
    void aRoleCarryingAnInlinePolicyAsksForADelete() {
        String roleName = createRole();
        inlineOnRole(roleName);
        iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_DELETE_POLICIES));
    }

    /** The group keeps the detach sentence for a managed policy, as the user already does. */
    @Test
    void aGroupCarryingAManagedPolicyAsksForADetach() {
        String groupName = createGroup();
        attachToGroup(groupName);
        iam("DeleteGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_DETACH_POLICIES));
    }

    /** The group's inline policy is a delete, the remedy that actually clears it. */
    @Test
    void aGroupCarryingAnInlinePolicyAsksForADelete() {
        String groupName = createGroup();
        putInline("PutGroupPolicy", "DeleteGroupPolicy", "GroupName", groupName);
        iam("DeleteGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_DELETE_POLICIES));
    }

    /** The user path is the model the other two follow, and it answers what it already did. */
    @Test
    void aUserCarryingAnInlinePolicyStillAsksForADelete() {
        String userName = createUser();
        putInline("PutUserPolicy", "DeleteUserPolicy", "UserName", userName);
        iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("DeleteConflict"))
                .body(MESSAGE, equalTo(MUST_DELETE_POLICIES));
    }

    /** The control: every dependency cleared in the order the refusals asked for, and it goes. */
    @Test
    void theRoleGoesOnceEachDependencyNamedIsCleared() {
        String roleName = createRole();
        String profileName = pairedProfile(roleName);
        String policyArn = attachToRole(roleName);
        String inlineName = inlineOnRole(roleName);

        iam("RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profileName)
                .formParam("RoleName", roleName).when().post("/").then().statusCode(200);
        iam("DeleteInstanceProfile").formParam("InstanceProfileName", profileName)
                .when().post("/").then().statusCode(200);
        iam("DetachRolePolicy").formParam("RoleName", roleName).formParam("PolicyArn", policyArn)
                .when().post("/").then().statusCode(200);
        iam("DeleteRolePolicy").formParam("RoleName", roleName).formParam("PolicyName", inlineName)
                .when().post("/").then().statusCode(200);
        iam("DeleteRole").formParam("RoleName", roleName).when().post("/").then().statusCode(200);
    }
}
