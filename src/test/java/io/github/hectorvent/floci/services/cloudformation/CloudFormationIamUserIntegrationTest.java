package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies CloudFormation provisioning and deprovisioning lifecycle for {@code AWS::IAM::User}
 * (regression test for issue #2490).
 */
@QuarkusTest
class CloudFormationIamUserIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/iam/aws4_request";

    @Test
    void deleteStackDeletesIamUserAndAllowsRecreation() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String userName = "probe-user-" + suffix;
        String stackName = "cfn-user-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "ProbeUser": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s"
                      }
                    }
                  },
                  "Outputs": {
                    "UserRef": {"Value": {"Ref": "ProbeUser"}},
                    "UserArn": {"Value": {"Fn::GetAtt": ["ProbeUser", "Arn"]}}
                  }
                }
                """.formatted(userName);

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String stackXml = describeStacks(stackId);
        assertEquals(userName, outputValue(stackXml, "UserRef"));
        String expectedArn = "arn:aws:iam::111122223333:user/" + userName;
        assertEquals(expectedArn, outputValue(stackXml, "UserArn"));

        // Verify IAM user exists and Arn matches
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<UserName>" + userName + "</UserName>"))
            .body(containsString("<Arn>" + expectedArn + "</Arn>"));

        // Delete the stack
        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        // Verify IAM user is gone (NoSuchEntity / 404)
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));

        // Redeploying with the same explicit UserName succeeds rather than failing with EntityAlreadyExists
        String newStackName = "cfn-user-stack-recreate-" + suffix;
        String newStackId = createStack(newStackName, template);
        awaitStackStatus(newStackId, "CREATE_COMPLETE");

        deleteStack(newStackName);
        awaitStackStatus(newStackId, "DELETE_COMPLETE");
    }

    @Test
    void deleteStackDeletesUserWithGeneratedName() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-genuser-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "GenUser": {
                      "Type": "AWS::IAM::User"
                    }
                  },
                  "Outputs": {
                    "UserRef": {"Value": {"Ref": "GenUser"}},
                    "UserArn": {"Value": {"Fn::GetAtt": ["GenUser", "Arn"]}}
                  }
                }
                """;

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String stackXml = describeStacks(stackId);
        String generatedUserName = outputValue(stackXml, "UserRef");
        assertNotNull(generatedUserName);
        assertEquals("arn:aws:iam::111122223333:user/" + generatedUserName, outputValue(stackXml, "UserArn"));

        // Verify generated user exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", generatedUserName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<UserName>" + generatedUserName + "</UserName>"))
            .body(containsString("<Arn>arn:aws:iam::111122223333:user/" + generatedUserName + "</Arn>"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        // Verify generated user is deleted
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", generatedUserName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    @Test
    void updateStackReconcilesGroupsPoliciesAndPath() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String userName = "update-user-" + suffix;
        String stackName = "cfn-update-user-stack-" + suffix;

        // Pre-create two groups
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateGroup")
            .formParam("GroupName", "group-a-" + suffix)
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateGroup")
            .formParam("GroupName", "group-b-" + suffix)
        .when().post("/").then().statusCode(200);

        String template1 = """
                {
                  "Resources": {
                    "TestUser": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s",
                        "Path": "/initial/",
                        "Groups": ["group-a-%s", "group-b-%s"],
                        "ManagedPolicyArns": [
                          "arn:aws:iam::aws:policy/ReadOnlyAccess",
                          "arn:aws:iam::aws:policy/PowerUserAccess"
                        ],
                        "Policies": [
                          {
                            "PolicyName": "keep-policy",
                            "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                          },
                          {
                            "PolicyName": "drop-policy",
                            "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                          }
                        ]
                      }
                    }
                  }
                }
                """.formatted(userName, suffix, suffix);

        String stackId = createStack(stackName, template1);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        // Verify initial state
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<Path>/initial/</Path>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedUserPolicies")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(containsString("ReadOnlyAccess"))
            .body(containsString("PowerUserAccess"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListUserPolicies")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<member>keep-policy</member>"))
            .body(containsString("<member>drop-policy</member>"));

        // Update stack: change path to /updated/, drop group-b, drop PowerUserAccess, drop drop-policy
        String template2 = """
                {
                  "Resources": {
                    "TestUser": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s",
                        "Path": "/updated/",
                        "Groups": ["group-a-%s"],
                        "ManagedPolicyArns": [
                          "arn:aws:iam::aws:policy/ReadOnlyAccess"
                        ],
                        "Policies": [
                          {
                            "PolicyName": "keep-policy",
                            "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                          }
                        ]
                      }
                    }
                  }
                }
                """.formatted(userName, suffix);

        updateStack(stackName, template2);
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        // Verify updated path
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<Path>/updated/</Path>"));

        // Verify managed policy PowerUserAccess was detached
        String attachedPolicies = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedUserPolicies")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200).extract().asString();
        assertThat(attachedPolicies, containsString("ReadOnlyAccess"));
        assertThat(attachedPolicies, org.hamcrest.Matchers.not(containsString("PowerUserAccess")));

        // Verify drop-policy was deleted
        String inlinePolicies = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListUserPolicies")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200).extract().asString();
        assertThat(inlinePolicies, containsString("<member>keep-policy</member>"));
        assertThat(inlinePolicies, org.hamcrest.Matchers.not(containsString("<member>drop-policy</member>")));

        // Verify group-b was removed
        String userGroups = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListGroupsForUser")
            .formParam("UserName", userName)
        .when().post("/").then().statusCode(200).extract().asString();
        assertThat(userGroups, containsString("group-a-" + suffix));
        assertThat(userGroups, org.hamcrest.Matchers.not(containsString("group-b-" + suffix)));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    private static String createStack(String stackName, String template) {
        return cfnQuery("CreateStack", stackName, template)
                .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private static void updateStack(String stackName, String template) {
        cfnQuery("UpdateStack", stackName, template)
                .then()
                .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        cfnQuery("DeleteStack", stackName)
                .then()
                .statusCode(200);
    }

    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return cfnQuery("DescribeStacks", stackId).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackName) {
        return cfnQuery(action, stackName, null);
    }

    private static Response cfnQuery(String action, String stackName, String template) {
        var req = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            req.formParam("TemplateBody", template);
        }
        return req.when().post("/");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
