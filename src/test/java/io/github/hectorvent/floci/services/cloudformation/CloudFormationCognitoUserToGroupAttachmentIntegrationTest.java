package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code AWS::Cognito::UserPoolUserToGroupAttachment} end to end. The pool, user and groups are
 * seeded through the Cognito API and passed in as parameters, so the stack owns only the
 * membership, and every assertion reads the membership back from Cognito. Each test deletes the
 * pool it seeded.
 */
@QuarkusTest
class CloudFormationCognitoUserToGroupAttachmentIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String USER = "user-one";

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "PoolId": { "Type": "String" },
                "GroupName": { "Type": "String" },
                "Username": { "Type": "String" }
              },
              "Resources": {
                "Attach": {
                  "Type": "AWS::Cognito::UserPoolUserToGroupAttachment",
                  "Properties": {
                    "UserPoolId": { "Ref": "PoolId" },
                    "GroupName": { "Ref": "GroupName" },
                    "Username": { "Ref": "Username" }
                  }
                }
              },
              "Outputs": {
                "AttachRef": { "Value": { "Ref": "Attach" } }
              }
            }
            """;

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser(COGNITO_CONTENT_TYPE, Parser.JSON);
    }

    @Test
    void changingTheGroupReplacesTheMembershipAndDeleteToleratesOneRemovedOutOfBand()
            throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String poolId = seedPoolUserAndGroups("cfn-attach-" + suffix);
        String stackName = "cfn-cognito-attach-" + suffix;

        String stackId = stackCall("CreateStack", stackName, poolId, "grp-a");
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        assertEquals(poolId + "|grp-a|" + USER, outputValue(stackId, "AttachRef"));
        groupsOf(poolId).body("Groups.GroupName", contains("grp-a"));

        stackCall("UpdateStack", stackName, poolId, "grp-b");
        awaitStackStatus(stackId, "UPDATE_COMPLETE");
        assertEquals(poolId + "|grp-b|" + USER, outputValue(stackId, "AttachRef"));
        groupsOf(poolId).body("Groups.GroupName", contains("grp-b"));

        cognito("AdminRemoveUserFromGroup", """
                {"UserPoolId": "%s", "GroupName": "grp-b", "Username": "%s"}
                """.formatted(poolId, USER)).statusCode(200);
        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
        groupsOf(poolId).body("Groups", empty());
        deletePool(poolId);
    }

    @Test
    void deletingTheStackRemovesTheMembership() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String poolId = seedPoolUserAndGroups("cfn-attach-del-" + suffix);
        createGroup(poolId, "grp|c");
        String stackName = "cfn-cognito-attach-del-" + suffix;

        String stackId = stackCall("CreateStack", stackName, poolId, "grp|c");
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        assertEquals(poolId + "|grp|c|" + USER, outputValue(stackId, "AttachRef"));
        groupsOf(poolId).body("Groups.GroupName", contains("grp|c"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
        groupsOf(poolId).body("Groups", empty());
        deletePool(poolId);
    }

    @Test
    void anAliasUpdateOfTheSameUserRollsBackAndKeepsTheMembership() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String email = "user-" + suffix + "@example.com";
        String poolId = cognito("CreateUserPool", """
                {"PoolName": "cfn-attach-alias-%s", "AliasAttributes": ["email"]}
                """.formatted(suffix))
                .statusCode(200)
                .extract().path("UserPool.Id");
        cognito("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "%s", "MessageAction": "SUPPRESS",
                 "UserAttributes": [{"Name": "email", "Value": "%s"}, {"Name": "email_verified", "Value": "true"}]}
                """.formatted(poolId, USER, email)).statusCode(200);
        createGroup(poolId, "grp-a");
        String stackName = "cfn-cognito-attach-alias-" + suffix;
        String aliasRef = poolId + "|grp-a|" + email;

        String stackId = stackCall("CreateStack", stackName, poolId, "grp-a", email);
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        assertEquals(aliasRef, outputValue(stackId, "AttachRef"));
        groupsOf(poolId).body("Groups.GroupName", contains("grp-a"));

        stackCall("UpdateStack", stackName, poolId, "grp-a", USER);
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(aliasRef, outputValue(stackId, "AttachRef"));
        groupsOf(poolId).body("Groups.GroupName", contains("grp-a"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
        groupsOf(poolId).body("Groups", empty());
        deletePool(poolId);
    }

    private String seedPoolUserAndGroups(String poolName) {
        String poolId = cognito("CreateUserPool", "{\"PoolName\": \"" + poolName + "\"}")
                .statusCode(200)
                .extract().path("UserPool.Id");
        cognito("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "%s", "MessageAction": "SUPPRESS"}
                """.formatted(poolId, USER)).statusCode(200);
        for (String group : new String[] {"grp-a", "grp-b"}) {
            createGroup(poolId, group);
        }
        return poolId;
    }

    private void createGroup(String poolId, String groupName) {
        cognito("CreateGroup", """
                {"UserPoolId": "%s", "GroupName": "%s"}
                """.formatted(poolId, groupName)).statusCode(200);
    }

    private void deletePool(String poolId) {
        cognito("DeleteUserPool", """
                {"UserPoolId": "%s"}
                """.formatted(poolId)).statusCode(200);
    }

    private ValidatableResponse groupsOf(String poolId) {
        return cognito("AdminListGroupsForUser", """
                {"UserPoolId": "%s", "Username": "%s"}
                """.formatted(poolId, USER)).statusCode(200);
    }

    /** CreateStack or UpdateStack with the attachment template for the seeded user; returns the stack id. */
    private static String stackCall(String action, String stackName, String poolId, String groupName) {
        return stackCall(action, stackName, poolId, groupName, USER);
    }

    /** CreateStack or UpdateStack with the attachment template; returns the stack id. */
    private static String stackCall(String action, String stackName, String poolId, String groupName,
                                    String username) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "PoolId")
            .formParam("Parameters.member.1.ParameterValue", poolId)
            .formParam("Parameters.member.2.ParameterKey", "GroupName")
            .formParam("Parameters.member.2.ParameterValue", groupName)
            .formParam("Parameters.member.3.ParameterKey", "Username")
            .formParam("Parameters.member.3.ParameterValue", username)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        return XmlPath.from(xml).getString(action + "Response." + action + "Result.StackId");
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String describeStacks(String stackId) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (status.equals(XmlPath.from(xml).getString(
                    "DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus"))) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String outputValue(String stackId, String key) {
        return XmlPath.from(describeStacks(stackId)).getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member.find { it.OutputKey == '"
                        + key + "' }.OutputValue");
    }

    private ValidatableResponse cognito(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs(COGNITO_CONTENT_TYPE, ContentType.TEXT)))
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + target)
            .contentType(COGNITO_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }
}
