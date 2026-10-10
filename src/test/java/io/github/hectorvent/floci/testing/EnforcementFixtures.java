package io.github.hectorvent.floci.testing;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Takes an IAM user or an S3 bucket that an enforcement test created back out of the shared
 * application. A {@code @QuarkusTest} shares one application with every other test class, so a
 * fixture left behind outlives the class that made it and is visible to the next class that lists
 * users or buckets unscoped. Registered with {@link PartitionCleanup} as each fixture is made.
 *
 * <p>Both read back what is attached rather than taking it as an argument, and both return
 * quietly when the thing is not there. That is what makes them safe to register before the create
 * runs: a setup that fails part way through still has the whole fixture taken away, and one that
 * created nothing leaves these with nothing to do.
 *
 * <p>The port is passed in because Quarkus points RestAssured at the application only for the
 * duration of a test method and resets the port afterwards, so a teardown has to say where the
 * application is. The test port is random, which leaves an injected {@code @TestHTTPResource}
 * URI as the only place to read it from.
 */
public final class EnforcementFixtures {

    private EnforcementFixtures() {
        // Utility class, prevent instantiation
    }

    /**
     * Deletes an IAM user, and first everything IAM wants gone before it will: inline policies,
     * access keys, group memberships and attached managed policies. The IAM API reference lists
     * all four among the items to remove "before attempting to delete a user", and Floci answers
     * {@code DeleteConflict} for each of them.
     *
     * @param authorization an IAM-scoped header. Signing with an account id reaches that
     *                      account's namespace as its root stand-in, which is how a user outside
     *                      the default account is removed.
     */
    public static void removeUser(int port, String authorization, String userName) {
        if (iam(port, authorization, "GetUser", "UserName", userName).extract().statusCode() == 404) {
            return;
        }
        for (String policyName : iam(port, authorization, "ListUserPolicies", "UserName", userName)
                .statusCode(200).extract().xmlPath()
                .getList("ListUserPoliciesResponse.ListUserPoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteUserPolicy", "UserName", userName,
                    "PolicyName", policyName).statusCode(200);
        }
        for (String accessKeyId : iam(port, authorization, "ListAccessKeys", "UserName", userName)
                .statusCode(200).extract().xmlPath()
                .getList("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member"
                        + ".AccessKeyId", String.class)) {
            iam(port, authorization, "DeleteAccessKey", "UserName", userName,
                    "AccessKeyId", accessKeyId).statusCode(200);
        }
        for (String groupName : iam(port, authorization, "ListGroupsForUser",
                "UserName", userName).statusCode(200).extract().xmlPath()
                .getList("ListGroupsForUserResponse.ListGroupsForUserResult.Groups.member"
                        + ".GroupName", String.class)) {
            iam(port, authorization, "RemoveUserFromGroup", "GroupName", groupName,
                    "UserName", userName).statusCode(200);
        }
        for (String policyArn : iam(port, authorization, "ListAttachedUserPolicies",
                "UserName", userName).statusCode(200).extract().xmlPath()
                .getList("ListAttachedUserPoliciesResponse.ListAttachedUserPoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class)) {
            iam(port, authorization, "DetachUserPolicy", "UserName", userName,
                    "PolicyArn", policyArn).statusCode(200);
        }
        iam(port, authorization, "DeleteUser", "UserName", userName).statusCode(200);
    }

    /**
     * Deletes an S3 bucket, and first whatever it still holds: "All objects (including all object
     * versions and delete markers) in the bucket must be deleted before the bucket itself can be
     * deleted" (S3 API reference, {@code DeleteBucket}), which Floci answers
     * {@code BucketNotEmpty} for.
     *
     * @param authorization an S3-scoped header for an identity allowed to read and delete here
     */
    /**
     * Deletes a managed policy, and first the versions and attachments IAM wants gone before it
     * will. Returns quietly when the policy is already absent, so a caller can register the
     * removal before creating it.
     */
    public static void removePolicy(int port, String authorization, String policyArn) {
        if (iam(port, authorization, "GetPolicy", "PolicyArn", policyArn)
                .extract().statusCode() == 404) {
            return;
        }
        iam(port, authorization, "DeletePolicy", "PolicyArn", policyArn).statusCode(200);
    }

    /**
     * Deletes a role, and first the policies it carries: Floci answers {@code DeleteConflict},
     * "must detach all policies first", for a role holding either an inline or a managed one.
     */
    public static void removeRole(int port, String authorization, String roleName) {
        if (iam(port, authorization, "GetRole", "RoleName", roleName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String policyName : iam(port, authorization, "ListRolePolicies", "RoleName", roleName)
                .statusCode(200).extract().xmlPath()
                .getList("ListRolePoliciesResponse.ListRolePoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteRolePolicy", "RoleName", roleName,
                    "PolicyName", policyName).statusCode(200);
        }
        for (String policyArn : iam(port, authorization, "ListAttachedRolePolicies",
                "RoleName", roleName).statusCode(200).extract().xmlPath()
                .getList("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class)) {
            iam(port, authorization, "DetachRolePolicy", "RoleName", roleName,
                    "PolicyArn", policyArn).statusCode(200);
        }
        iam(port, authorization, "DeleteRole", "RoleName", roleName).statusCode(200);
    }

    /**
     * Deletes an instance profile, taking any role out of it first: AWS requires the profile to
     * hold no role before it can go, and the role is a separate resource with its own removal.
     */
    public static void removeInstanceProfile(int port, String authorization, String profileName) {
        if (iam(port, authorization, "GetInstanceProfile", "InstanceProfileName", profileName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String roleName : iam(port, authorization, "GetInstanceProfile",
                "InstanceProfileName", profileName).statusCode(200).extract().xmlPath()
                .getList("GetInstanceProfileResponse.GetInstanceProfileResult.InstanceProfile"
                        + ".Roles.member.RoleName", String.class)) {
            iam(port, authorization, "RemoveRoleFromInstanceProfile",
                    "InstanceProfileName", profileName, "RoleName", roleName).statusCode(200);
        }
        iam(port, authorization, "DeleteInstanceProfile",
                "InstanceProfileName", profileName).statusCode(200);
    }

    /** Deletes a group, and first the users in it, which IAM wants gone before it will. */
    public static void removeGroup(int port, String authorization, String groupName) {
        if (iam(port, authorization, "GetGroup", "GroupName", groupName)
                .extract().statusCode() == 404) {
            return;
        }
        for (String userName : iam(port, authorization, "GetGroup", "GroupName", groupName)
                .statusCode(200).extract().xmlPath()
                .getList("GetGroupResponse.GetGroupResult.Users.member.UserName", String.class)) {
            iam(port, authorization, "RemoveUserFromGroup",
                    "GroupName", groupName, "UserName", userName).statusCode(200);
        }
        for (String policyName : iam(port, authorization, "ListGroupPolicies",
                "GroupName", groupName).statusCode(200).extract().xmlPath()
                .getList("ListGroupPoliciesResponse.ListGroupPoliciesResult.PolicyNames.member",
                        String.class)) {
            iam(port, authorization, "DeleteGroupPolicy", "GroupName", groupName,
                    "PolicyName", policyName).statusCode(200);
        }
        iam(port, authorization, "DeleteGroup", "GroupName", groupName).statusCode(200);
    }

    /**
     * Deletes a virtual MFA device, a SAML provider or an OIDC provider, each named by the ARN it
     * was created with. One method because the three take the same shape: an ARN parameter, a
     * delete that needs nothing removed first, and no read that distinguishes absent from denied,
     * so a delete of something already gone is accepted rather than asserted on.
     */
    public static void removeByArn(int port, String authorization, String action,
                                   String parameter, String arn) {
        iam(port, authorization, action, parameter, arn);
    }

    public static void removeBucket(int port, String authorization, String bucket) {
        if (s3(port, authorization).when().head("/" + bucket)
                .then().extract().statusCode() == 404) {
            return;
        }
        for (String key : s3(port, authorization)
                .queryParam("list-type", "2")
            .when()
                .get("/" + bucket)
            .then()
                .statusCode(200)
                .extract().xmlPath().getList("ListBucketResult.Contents.Key", String.class)) {
            s3(port, authorization).when().delete("/" + bucket + "/" + key)
                    .then().statusCode(204);
        }
        s3(port, authorization).when().delete("/" + bucket).then().statusCode(204);
    }

    private static ValidatableResponse iam(int port, String authorization, String action,
                                           String... params) {
        RequestSpecification request = given().port(port)
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static RequestSpecification s3(int port, String authorization) {
        return given().port(port).header("Authorization", authorization);
    }
}
