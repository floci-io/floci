package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
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
 * membership, and every assertion reads the membership back from Cognito.
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
    }

    @Test
    void deletingTheStackRemovesTheMembership() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String poolId = seedPoolUserAndGroups("cfn-attach-del-" + suffix);
        String stackName = "cfn-cognito-attach-del-" + suffix;

        String stackId = stackCall("CreateStack", stackName, poolId, "grp-a");
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        groupsOf(poolId).body("Groups.GroupName", contains("grp-a"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
        groupsOf(poolId).body("Groups", empty());
    }

    private String seedPoolUserAndGroups(String poolName) {
        String poolId = cognito("CreateUserPool", "{\"PoolName\": \"" + poolName + "\"}")
                .statusCode(200)
                .extract().path("UserPool.Id");
        cognito("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "%s", "MessageAction": "SUPPRESS"}
                """.formatted(poolId, USER)).statusCode(200);
        for (String group : new String[] {"grp-a", "grp-b"}) {
            cognito("CreateGroup", """
                    {"UserPoolId": "%s", "GroupName": "%s"}
                    """.formatted(poolId, group)).statusCode(200);
        }
        return poolId;
    }

    private ValidatableResponse groupsOf(String poolId) {
        return cognito("AdminListGroupsForUser", """
                {"UserPoolId": "%s", "Username": "%s"}
                """.formatted(poolId, USER)).statusCode(200);
    }

    /** CreateStack or UpdateStack with the attachment template; returns the stack id. */
    private static String stackCall(String action, String stackName, String poolId, String groupName) {
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
            .formParam("Parameters.member.3.ParameterValue", USER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
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
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String outputValue(String stackId, String key) {
        return describeStacks(stackId).split("<OutputKey>" + key + "</OutputKey>")[1]
                .split("<OutputValue>")[1].split("</OutputValue>")[0];
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
