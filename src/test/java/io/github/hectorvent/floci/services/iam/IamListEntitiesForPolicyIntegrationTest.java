package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        String policyArn = XmlParser.extractFirst(createPolicy, "Arn", null);
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

        // Now the response exposes all three entity groups with SDK-readable names and ids.
        String entities = listEntities(policyArn);
        assertEquals(List.of("PolicyGroups", "PolicyUsers", "PolicyRoles", "IsTruncated"),
                XmlParser.childElementNames(entities, "ListEntitiesForPolicyResult"));
        List<Map<String, String>> members = XmlParser.extractGroups(entities, "member");
        assertMember(members, "RoleName", roleName, "RoleId");
        assertMember(members, "UserName", userName, "UserId");
        assertMember(members, "GroupName", groupName, "GroupId");
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

    private static void assertMember(List<Map<String, String>> members, String nameElement,
                                     String expectedName, String idElement) {
        assertTrue(members.stream().anyMatch(member -> expectedName.equals(member.get(nameElement))
                        && member.get(idElement) != null && !member.get(idElement).isBlank()),
                () -> expectedName + " should have a non-blank " + idElement + "; members were: " + members);
    }
}
