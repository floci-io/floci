package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check that CloudFormation provisions an AWS::Logs::LogGroup for real (into
 * CloudWatchLogsService) rather than stubbing it. Log groups are metadata (no container), so the
 * test is Docker-free.
 */
@QuarkusTest
class CloudFormationLogsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LOGS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/logs/aws4_request";

    @Test
    void createStackProvisionsLogGroupVisibleToCloudWatchLogs() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String logGroupName = "/cfn/test/" + suffix;
        String stackName = "cfn-logs-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {
                        "LogGroupName": "%s",
                        "RetentionInDays": 7
                      }
                    }
                  },
                  "Outputs": {
                    "GroupArn": {"Value": {"Fn::GetAtt": ["LogGroup", "Arn"]}}
                  }
                }
                """.formatted(logGroupName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Stack completes and Fn::GetAtt(LogGroup, Arn) resolves to the log group ARN.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("log-group:" + logGroupName + ":*"));

        // The log group really exists in CloudWatch Logs.
        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupNamePrefix\":\"" + logGroupName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(logGroupName));
    }

    @Test
    void updateStackReconcilesExistingLogGroupInsteadOfFailing() {
        // Regression for #1965: UpdateStack used to call CreateLogGroup unconditionally on every
        // update, so a stack whose log group was untouched between deploys rolled back with
        // "The specified log group already exists".
        String suffix = Long.toString(System.nanoTime(), 36);
        String logGroupName = "/cfn/test/" + suffix;
        String stackName = "cfn-logs-update-stack-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(logGroupName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(logGroupName, 14))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupNamePrefix\":\"" + logGroupName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("\"retentionInDays\":14"));
    }

    @Test
    void updateStackKeepsAutoGeneratedLogGroupNameAcrossUpdates() {
        // No explicit LogGroupName: the physical name must stay stable across updates (matching
        // CloudFormation's real behavior for auto-generated resource names), not get replaced with a
        // freshly generated random name on every no-op update.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-logs-autoname-stack-" + suffix;
        String templateNoRetention = """
                {
                  "Resources": {
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {}
                    }
                  },
                  "Outputs": {
                    "GroupArn": {"Value": {"Fn::GetAtt": ["LogGroup", "Arn"]}}
                  }
                }
                """;
        String templateWithRetention = """
                {
                  "Resources": {
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {
                        "RetentionInDays": 30
                      }
                    }
                  },
                  "Outputs": {
                    "GroupArn": {"Value": {"Fn::GetAtt": ["LogGroup", "Arn"]}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateNoRetention)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String beforeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        String arnBefore = XmlParser.extractPairs(beforeXml, "Outputs", "OutputKey", "OutputValue")
                .get("GroupArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateWithRetention)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String afterXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .extract().asString();
        String arnAfter = XmlParser.extractPairs(afterXml, "Outputs", "OutputKey", "OutputValue")
                .get("GroupArn");

        assertEquals(arnBefore, arnAfter);
    }

    @Test
    void updateStackReplacesLogGroupWhenNameChanges() {
        // LogGroupName isn't updatable in place on real AWS - changing it replaces the resource. The
        // old group must actually be gone afterward, not just abandoned.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-logs-rename-stack-" + suffix;
        String oldName = "/cfn/test/old-" + suffix;
        String newName = "/cfn/test/new-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(oldName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(newName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString("log-group:" + newName + ":*"));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupNamePrefix\":\"" + oldName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(oldName)));
    }

    @Test
    void updateStackRenameCollision_doesNotDeleteOldLogGroupBeforeVerifyingNewOneWorks() {
        // If the new name is already taken, CreateLogGroup for it fails and the update rolls back.
        // The old group must still be there afterward - deleting it before the new one is confirmed
        // creatable would mean rollback can't restore what was already destroyed.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-logs-collision-stack-" + suffix;
        String oldName = "/cfn/test/collision-old-" + suffix;
        String takenName = "/cfn/test/collision-taken-" + suffix;

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.CreateLogGroup")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupName\":\"" + takenName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(oldName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(takenName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("UPDATE_ROLLBACK_COMPLETE"));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupNamePrefix\":\"" + oldName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(oldName));
    }

    @Test
    void updateStackRemovingExplicitName_generatesFreshNameInsteadOfKeepingOld() {
        // Going from an explicit LogGroupName to none is a naming-mode change, which is itself a
        // replacement on real AWS - the group must get a freshly generated name, not silently keep
        // reconciling under the removed explicit name.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-logs-name-removed-stack-" + suffix;
        String explicitName = "/cfn/test/explicit-" + suffix;
        String templateNoName = """
                {
                  "Resources": {
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {}
                    }
                  },
                  "Outputs": {
                    "GroupArn": {"Value": {"Fn::GetAtt": ["LogGroup", "Arn"]}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", logGroupTemplate(explicitName, 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateNoName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("log-group:" + explicitName + ":*")));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
            .contentType("application/x-amz-json-1.1")
            .body("{\"logGroupNamePrefix\":\"" + explicitName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(explicitName)));
    }

    private static String logGroupTemplate(String logGroupName, int retentionInDays) {
        return """
                {
                  "Resources": {
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {
                        "LogGroupName": "%s",
                        "RetentionInDays": %d
                      }
                    }
                  },
                  "Outputs": {
                    "GroupArn": {"Value": {"Fn::GetAtt": ["LogGroup", "Arn"]}}
                  }
                }
                """.formatted(logGroupName, retentionInDays);
    }
}
