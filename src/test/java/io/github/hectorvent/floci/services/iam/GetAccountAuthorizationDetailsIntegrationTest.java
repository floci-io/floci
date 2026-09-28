package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Verifies GetAccountAuthorizationDetails against the real wire shape (UserDetail, GroupDetail,
 * RoleDetail, ManagedPolicyDetail). IAM state is shared across the whole test suite and this
 * action returns everything unfiltered, so every assertion locates its own entities by name or
 * ARN with a Groovy {@code find}, rather than asserting on the response as a whole.
 */
@QuarkusTest
class GetAccountAuthorizationDetailsIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static io.restassured.specification.RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    @Test
    void userDetailIncludesInlinePolicyGroupMembershipAttachedPolicyAndBoundary() {
        String s = suffix();
        String user = "aad-user-" + s;
        String group = "aad-group-" + s;

        iam("CreateUser").formParam("UserName", user)
            .when().post("/").then().statusCode(200);
        iam("PutUserPolicy").formParam("UserName", user)
            .formParam("PolicyName", "inline").formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200);
        iam("CreateGroup").formParam("GroupName", group)
            .when().post("/").then().statusCode(200);
        iam("AddUserToGroup").formParam("GroupName", group).formParam("UserName", user)
            .when().post("/").then().statusCode(200);
        iam("AttachUserPolicy").formParam("UserName", user)
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess")
            .when().post("/").then().statusCode(200);
        String boundaryArn = iam("CreatePolicy").formParam("PolicyName", "aad-boundary-" + s)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        iam("PutUserPermissionsBoundary").formParam("UserName", user).formParam("PermissionsBoundary", boundaryArn)
            .when().post("/").then().statusCode(200);

        iam("GetAccountAuthorizationDetails")
        .when().post("/").then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".UserDetailList.member.find { it.UserName == '" + user + "' }.UserPolicyList.member.PolicyName",
                    equalTo("inline"))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".UserDetailList.member.find { it.UserName == '" + user + "' }.GroupList.member",
                    equalTo(group))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".UserDetailList.member.find { it.UserName == '" + user + "' }"
                    + ".AttachedManagedPolicies.member.PolicyArn",
                    equalTo("arn:aws:iam::aws:policy/ReadOnlyAccess"))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".UserDetailList.member.find { it.UserName == '" + user + "' }"
                    + ".PermissionsBoundary.PermissionsBoundaryArn",
                    equalTo(boundaryArn))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".UserDetailList.member.find { it.UserName == '" + user + "' }"
                    + ".PermissionsBoundary.PermissionsBoundaryType",
                    equalTo("Policy"));
    }

    @Test
    void groupDetailIncludesInlineAndAttachedPolicies() {
        String s = suffix();
        String group = "aad-group2-" + s;

        iam("CreateGroup").formParam("GroupName", group)
            .when().post("/").then().statusCode(200);
        iam("PutGroupPolicy").formParam("GroupName", group)
            .formParam("PolicyName", "inline").formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200);
        iam("AttachGroupPolicy").formParam("GroupName", group)
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess")
            .when().post("/").then().statusCode(200);

        iam("GetAccountAuthorizationDetails")
        .when().post("/").then()
            .statusCode(200)
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".GroupDetailList.member.find { it.GroupName == '" + group + "' }"
                    + ".GroupPolicyList.member.PolicyName",
                    equalTo("inline"))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".GroupDetailList.member.find { it.GroupName == '" + group + "' }"
                    + ".AttachedManagedPolicies.member.PolicyArn",
                    equalTo("arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"));
    }

    @Test
    void roleDetailIncludesAssumeRolePolicyInstanceProfileAndAttachedPolicy() {
        String s = suffix();
        String role = "aad-role-" + s;
        String profile = "aad-profile-" + s;

        iam("CreateRole").formParam("RoleName", role).formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        iam("CreateInstanceProfile").formParam("InstanceProfileName", profile)
            .when().post("/").then().statusCode(200);
        iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profile).formParam("RoleName", role)
            .when().post("/").then().statusCode(200);
        iam("AttachRolePolicy").formParam("RoleName", role)
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
            .when().post("/").then().statusCode(200);

        iam("GetAccountAuthorizationDetails")
        .when().post("/").then()
            .statusCode(200)
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".RoleDetailList.member.find { it.RoleName == '" + role + "' }"
                    + ".InstanceProfileList.member.InstanceProfileName",
                    equalTo(profile))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".RoleDetailList.member.find { it.RoleName == '" + role + "' }"
                    + ".AttachedManagedPolicies.member.PolicyArn",
                    equalTo("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"))
            // RoleDetail's own AssumeRolePolicyDocument, not just the one embedded via
            // InstanceProfileList's role subset.
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".RoleDetailList.member.find { it.RoleName == '" + role + "' }.AssumeRolePolicyDocument",
                    containsString("ec2.amazonaws.com"));
    }

    @Test
    void localPolicyIncludesVersionListAndAttachmentCountScopedToThisAccount() {
        String s = suffix();
        String policyName = "aad-policy-" + s;
        String user = "aad-policy-user-" + s;

        String arn = iam("CreatePolicy").formParam("PolicyName", policyName)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        iam("CreatePolicyVersion").formParam("PolicyArn", arn)
            .formParam("PolicyDocument",
                    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"s3:PutObject\",\"Resource\":\"*\"}]}")
            .formParam("SetAsDefault", "true")
            .when().post("/").then().statusCode(200);
        iam("CreateUser").formParam("UserName", user)
            .when().post("/").then().statusCode(200);
        iam("AttachUserPolicy").formParam("UserName", user).formParam("PolicyArn", arn)
            .when().post("/").then().statusCode(200);

        iam("GetAccountAuthorizationDetails")
        .when().post("/").then()
            .statusCode(200)
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".Policies.member.find { it.Arn == '" + arn + "' }.AttachmentCount",
                    equalTo("1"))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".Policies.member.find { it.Arn == '" + arn + "' }.PolicyVersionList.member.VersionId",
                    hasItem("v2"))
            .body("GetAccountAuthorizationDetailsResponse.GetAccountAuthorizationDetailsResult"
                    + ".Policies.member.find { it.Arn == '" + arn + "' }.PolicyVersionList.member.Document",
                    hasItem(containsString("PutObject")));
    }
}
