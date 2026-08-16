package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The AWS SDKs and the Terraform provider read a role's, policy's or user's tags off the
 * Create/Get response rather than by calling List*Tags. Tags were stored and were readable
 * through ListRoleTags, but never serialized into these responses, so every tagged resource
 * read back untagged and produced a permanent diff.
 *
 * <p>The inclusion is asymmetric, and the negative cases below pin that half: IAM's
 * resource-listing operations deliberately return a subset of attributes, so ListRoles,
 * ListUsers and ListPolicies must keep omitting tags.
 */
@QuarkusTest
class IamResourceTagsInResponseTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260807/us-east-1/iam/aws4_request";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static io.restassured.specification.RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    // ── Detail operations must return tags ────────────────────────────────────

    @Test
    void createAndGetRoleEchoTheirTags() {
        String role = "TagEchoRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Tags.member.1.Key", "Environment")
            .formParam("Tags.member.1.Value", "dev")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Tags>"))
            .body(containsString("<Key>Environment</Key>"))
            .body(containsString("<Value>dev</Value>"));

        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>Environment</Key>"))
            .body(containsString("<Value>dev</Value>"));
    }

    @Test
    void getRoleOmitsTheTagsElementEntirelyWhenUntagged() {
        String role = "UntaggedEchoRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);

        // An empty <Tags/> would read back as a tag set the caller never configured.
        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<Tags>")));
    }

    @Test
    void tagsAddedAfterCreationAppearOnGetRole() {
        String role = "LaterTaggedRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);

        iam("TagRole")
            .formParam("RoleName", role)
            .formParam("Tags.member.1.Key", "Team")
            .formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(200);

        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>Team</Key>"))
            .body(containsString("<Value>platform</Value>"));
    }

    @Test
    void createServiceLinkedRoleEchoesNoTagsButStillSerializesTheRole() {
        iam("CreateServiceLinkedRole")
            .formParam("AWSServiceName", "elasticache.amazonaws.com")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<RoleName>"))
            .body(not(containsString("<Tags>")));
    }

    @Test
    void createAndGetPolicyEchoTheirTags() {
        String policyName = "TagEchoPolicy";

        String arn = iam("CreatePolicy")
            .formParam("PolicyName", policyName)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Tags.member.1.Key", "Owner")
            .formParam("Tags.member.1.Value", "data")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        iam("GetPolicy")
            .formParam("PolicyArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Value>data</Value>"));
    }

    @Test
    void createAndGetUserEchoTheirTags() {
        String user = "TagEchoUser";

        iam("CreateUser")
            .formParam("UserName", user)
            .formParam("Tags.member.1.Key", "CostCenter")
            .formParam("Tags.member.1.Value", "1234")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>CostCenter</Key>"));

        iam("GetUser")
            .formParam("UserName", user)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>CostCenter</Key>"))
            .body(containsString("<Value>1234</Value>"));
    }

    // ── Listing operations must keep omitting tags ────────────────────────────

    @Test
    void listRolesOmitsTagsEvenForATaggedRole() {
        iam("CreateRole")
            .formParam("RoleName", "ListSubsetRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Tags.member.1.Key", "ListRolesMarker")
            .formParam("Tags.member.1.Value", "must-not-be-listed")
        .when().post("/").then().statusCode(200);

        // "IAM resource-listing operations return a subset of the available attributes for the
        // resource. This operation does not return the following attributes, even though they
        // are an attribute of the returned object: PermissionsBoundary, RoleLastUsed, Tags."
        iam("ListRoles")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<RoleName>ListSubsetRole</RoleName>"))
            .body(not(containsString("<Tags>")))
            .body(not(containsString("must-not-be-listed")));
    }

    @Test
    void listRolesStillReturnsDescriptionWhichIsNotInTheExcludedSet() {
        iam("CreateRole")
            .formParam("RoleName", "DescribedListRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Description", "kept-in-listings")
        .when().post("/").then().statusCode(200);

        iam("ListRoles")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("kept-in-listings"));
    }

    @Test
    void listUsersOmitsTagsEvenForATaggedUser() {
        iam("CreateUser")
            .formParam("UserName", "ListSubsetUser")
            .formParam("Tags.member.1.Key", "ListUsersMarker")
            .formParam("Tags.member.1.Value", "user-must-not-be-listed")
        .when().post("/").then().statusCode(200);

        iam("ListUsers")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<UserName>ListSubsetUser</UserName>"))
            .body(not(containsString("<Tags>")))
            .body(not(containsString("user-must-not-be-listed")));
    }

    @Test
    void listPoliciesOmitsTagsAndDescriptionEvenForATaggedPolicy() {
        iam("CreatePolicy")
            .formParam("PolicyName", "ListSubsetPolicy")
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Description", "policy-description-must-not-be-listed")
            .formParam("Tags.member.1.Key", "ListPoliciesMarker")
            .formParam("Tags.member.1.Value", "policy-must-not-be-listed")
        .when().post("/").then().statusCode(200);

        iam("ListPolicies")
            .formParam("Scope", "Local")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PolicyName>ListSubsetPolicy</PolicyName>"))
            .body(not(containsString("<Tags>")))
            .body(not(containsString("policy-must-not-be-listed")))
            .body(not(containsString("policy-description-must-not-be-listed")));
    }

    @Test
    void getGroupListsItsMembersWithoutTags() {
        iam("CreateUser")
            .formParam("UserName", "GroupMemberUser")
            .formParam("Tags.member.1.Key", "GroupMarker")
            .formParam("Tags.member.1.Value", "group-must-not-be-listed")
        .when().post("/").then().statusCode(200);

        iam("CreateGroup").formParam("GroupName", "TagSubsetGroup")
        .when().post("/").then().statusCode(200);

        iam("AddUserToGroup")
            .formParam("GroupName", "TagSubsetGroup")
            .formParam("UserName", "GroupMemberUser")
        .when().post("/").then().statusCode(200);

        iam("GetGroup")
            .formParam("GroupName", "TagSubsetGroup")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<UserName>GroupMemberUser</UserName>"))
            .body(not(containsString("<Tags>")))
            .body(not(containsString("group-must-not-be-listed")));
    }

    @Test
    void getInstanceProfileEmbedsItsRoleWithoutTags() {
        iam("CreateRole")
            .formParam("RoleName", "ProfileEmbeddedRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Tags.member.1.Key", "ProfileMarker")
            .formParam("Tags.member.1.Value", "profile-must-not-be-listed")
        .when().post("/").then().statusCode(200);

        iam("CreateInstanceProfile")
            .formParam("InstanceProfileName", "TagSubsetProfile")
        .when().post("/").then().statusCode(200);

        iam("AddRoleToInstanceProfile")
            .formParam("InstanceProfileName", "TagSubsetProfile")
            .formParam("RoleName", "ProfileEmbeddedRole")
        .when().post("/").then().statusCode(200);

        // GetInstanceProfile's own example response shows the embedded role as a subset.
        iam("GetInstanceProfile")
            .formParam("InstanceProfileName", "TagSubsetProfile")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<RoleName>ProfileEmbeddedRole</RoleName>"))
            .body(not(containsString("<Tags>")))
            .body(not(containsString("profile-must-not-be-listed")));
    }
}
