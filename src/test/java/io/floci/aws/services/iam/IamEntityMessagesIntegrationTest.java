package io.floci.aws.services.iam;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the exact error codes and messages IAM returns for duplicate policies, missing policy versions,
 * permissions boundaries, inline policies and instance profiles; the not-found wording of managed
 * policies themselves is pinned in {@link IamPolicyNotFoundWordingIntegrationTest}.
 * RestAssured is used deliberately so the raw wire messages are asserted, not SDK-rewritten ones.
 */
@QuarkusTest
class IamEntityMessagesIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String DOC = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** {@code params} lead with {@code "Action", <name>}, as every call site writes them. */
    private static void call(int status, String code, String message, String... params) {
        ValidatableResponse resp = QueryRequests
                .request(ACCOUNT, "iam", params[1], Arrays.copyOfRange(params, 2, params.length))
                .when().post("/").then().statusCode(status);
        if (code != null) {
            resp.body("ErrorResponse.Error.Code", equalTo(code))
                    .body("ErrorResponse.Error.Message", equalTo(message));
        }
    }

    private static String createPolicy(String name) {
        return QueryRequests.request(ACCOUNT, "iam", "CreatePolicy",
                        new String[] {"PolicyName", name, "PolicyDocument", DOC})
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    @Test
    void managedPolicyMessages() {
        String name = "pol-" + id();
        String arn = createPolicy(name);
        call(409, "EntityAlreadyExists",
                "A policy called " + name + " already exists. Duplicate names are not allowed.",
                "Action", "CreatePolicy", "PolicyName", name, "PolicyDocument", DOC);
        String missing = "arn:aws:iam::" + ACCOUNT + ":policy/missing-" + id();
        String notAttachable = " does not exist or is not attachable.";
        for (String action : new String[] {"GetPolicyVersion", "DeletePolicyVersion", "SetDefaultPolicyVersion"}) {
            call(404, "NoSuchEntity", "Policy " + arn + " version v99" + notAttachable,
                    "Action", action, "PolicyArn", arn, "VersionId", "v99");
        }
        String user = "u-" + id();
        call(200, null, null, "Action", "CreateUser", "UserName", user);

        // permissions boundary on a missing policy
        String scope = "Scope ARN: " + missing + notAttachable;
        call(404, "NoSuchEntity", scope,
                "Action", "PutUserPermissionsBoundary", "UserName", user, "PermissionsBoundary", missing);
        call(404, "NoSuchEntity", scope,
                "Action", "CreateUser", "UserName", "ub-" + id(), "PermissionsBoundary", missing);

        String role = "r-" + id();
        call(200, null, null, "Action", "CreateRole", "RoleName", role, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");
        call(404, "NoSuchEntity", scope,
                "Action", "PutRolePermissionsBoundary", "RoleName", role, "PermissionsBoundary", missing);
    }

    @Test
    void inlinePolicyMessages() {
        String user = "u-" + id();
        String group = "g-" + id();
        String role = "r-" + id();
        call(200, null, null, "Action", "CreateUser", "UserName", user);
        call(200, null, null, "Action", "CreateGroup", "GroupName", group);
        call(200, null, null, "Action", "CreateRole", "RoleName", role, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");
        call(404, "NoSuchEntity", "The user policy with name nope cannot be found.",
                "Action", "GetUserPolicy", "UserName", user, "PolicyName", "nope");
        call(404, "NoSuchEntity", "The user policy with name nope cannot be found.",
                "Action", "DeleteUserPolicy", "UserName", user, "PolicyName", "nope");
        call(404, "NoSuchEntity", "The group policy with name nope cannot be found.",
                "Action", "GetGroupPolicy", "GroupName", group, "PolicyName", "nope");
        call(404, "NoSuchEntity", "The group policy with name nope cannot be found.",
                "Action", "DeleteGroupPolicy", "GroupName", group, "PolicyName", "nope");
        call(404, "NoSuchEntity", "The role policy with name nope cannot be found.",
                "Action", "GetRolePolicy", "RoleName", role, "PolicyName", "nope");
        call(404, "NoSuchEntity", "The role policy with name nope cannot be found.",
                "Action", "DeleteRolePolicy", "RoleName", role, "PolicyName", "nope");
    }

    @Test
    void instanceProfileMessages() {
        String name = "ip-" + id();
        call(200, null, null, "Action", "CreateInstanceProfile", "InstanceProfileName", name);
        call(409, "EntityAlreadyExists", "Instance Profile " + name + " already exists.",
                "Action", "CreateInstanceProfile", "InstanceProfileName", name);
        String missing = "missing-" + id();
        String msg = "Instance Profile " + missing + " cannot be found.";
        call(404, "NoSuchEntity", msg, "Action", "GetInstanceProfile", "InstanceProfileName", missing);
        call(404, "NoSuchEntity", msg, "Action", "DeleteInstanceProfile", "InstanceProfileName", missing);
        call(404, "NoSuchEntity", msg, "Action", "TagInstanceProfile", "InstanceProfileName", missing,
                "Tags.member.1.Key", "k", "Tags.member.1.Value", "v");
        call(404, "NoSuchEntity", msg, "Action", "UntagInstanceProfile", "InstanceProfileName", missing,
                "TagKeys.member.1", "k");
    }
}
