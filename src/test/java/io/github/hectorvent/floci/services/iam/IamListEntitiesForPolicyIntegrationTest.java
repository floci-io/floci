package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the IAM ListEntitiesForPolicy query action returns the roles, users and groups a managed
 * policy is attached to under PolicyRoles/PolicyUsers/PolicyGroups, and an empty result for a policy
 * with no attachments. IAM is the Query API (form-encoded request, XML response); Docker-free.
 */
@QuarkusTest
class IamListEntitiesForPolicyIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private String iam(String action, String... kv) {
        var req = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            req = req.formParam(kv[i], kv[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().asString();
    }

    private String listEntities(String policyArn) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListEntitiesForPolicy")
            .formParam("PolicyArn", policyArn)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    @Test
    void listsRolesUsersAndGroupsAPolicyIsAttachedTo() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String policyName = "lefp-policy-" + suffix;
        String roleName = "lefp-role-" + suffix;
        String userName = "lefp-user-" + suffix;
        String groupName = "lefp-group-" + suffix;

        String createPolicy = iam("CreatePolicy",
                "PolicyName", policyName,
                "PolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}");
        Matcher m = Pattern.compile("<Arn>([^<]+)</Arn>").matcher(createPolicy);
        String policyArn = m.find() ? m.group(1) : null;
        assertNotNull(policyArn, "CreatePolicy should return an ARN");

        iam("CreateRole", "RoleName", roleName, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");
        iam("CreateUser", "UserName", userName);
        iam("CreateGroup", "GroupName", groupName);

        // A policy with no attachments yet: all three entity lists are empty.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListEntitiesForPolicy")
            .formParam("PolicyArn", policyArn)
        .when().post("/").then().statusCode(200)
            .body(allOf(not(containsString(roleName)), not(containsString(userName)), not(containsString(groupName))));

        iam("AttachRolePolicy", "RoleName", roleName, "PolicyArn", policyArn);
        iam("AttachUserPolicy", "UserName", userName, "PolicyArn", policyArn);
        iam("AttachGroupPolicy", "GroupName", groupName, "PolicyArn", policyArn);

        // Now each principal appears under the correct element.
        String entities = listEntities(policyArn);
        assertContainsInSection(entities, "PolicyRoles", "RoleName", roleName);
        assertContainsInSection(entities, "PolicyUsers", "UserName", userName);
        assertContainsInSection(entities, "PolicyGroups", "GroupName", groupName);

        // AWS returns the entity id alongside each name; SDK callers read groupId()/userId()/roleId().
        org.junit.jupiter.api.Assertions.assertTrue(
                entities.contains("<RoleId>") && entities.contains("<UserId>") && entities.contains("<GroupId>"),
                "each member should carry its entity id, was: " + entities);
    }

    @Test
    void unknownPolicyArnReturnsNoSuchEntity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListEntitiesForPolicy")
            .formParam("PolicyArn", "arn:aws:iam::000000000000:policy/does-not-exist-"
                    + Long.toString(System.nanoTime(), 36))
        .when().post("/").then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    private static void assertContainsInSection(String xml, String section, String elem, String value) {
        Matcher m = Pattern.compile("<" + section + ">(.*?)</" + section + ">", Pattern.DOTALL).matcher(xml);
        String body = m.find() ? m.group(1) : "";
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("<" + elem + ">" + value + "</" + elem + ">"),
                value + " should appear under " + section + " as <" + elem + ">; section was: " + body);
    }
}
