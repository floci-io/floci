package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeleteStack deletes every resource the stack manages, whatever status the last operation left it
 * in. A failed update whose rollback cannot restore a resource leaves it {@code UPDATE_FAILED} with
 * its physical id, and the stack in {@code UPDATE_ROLLBACK_FAILED}; deleting that stack must still
 * delete the resource rather than report {@code DELETE_COMPLETE} and leave it behind
 * (floci-io/floci#4467).
 */
@QuarkusTest
class CloudFormationDeleteAfterFailedRollbackIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260926/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260926/us-east-1/iam/aws4_request";
    private static final String LOGS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260926/us-east-1/logs/aws4_request";

    @Test
    void deleteStackAfterUpdateRollbackFailedDeletesUpdateFailedResources() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "delete-after-rollback-failed-" + suffix;
        String roleName = "delete-after-rollback-failed-role-" + suffix;
        String logGroup = "/delete-after-rollback-failed/" + suffix + "/lg";
        String outsideGroup = "/delete-after-rollback-failed/" + suffix + "/outside";

        logs("CreateLogGroup", "{\"logGroupName\":\"" + outsideGroup + "\"}").then().statusCode(200);
        try {
            String stackId = XmlParser.extractFirst(
                    cfn("CreateStack", stackName, template(roleName, logGroup, null)).then().statusCode(200)
                            .extract().asString(),
                    "StackId", null);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // Role and LogGroup both change, then Dup collides with the group created outside the stack.
            // Neither type rolls an update back, so both are left UPDATE_FAILED with their physical ids.
            cfn("UpdateStack", stackName, template(roleName, logGroup, outsideGroup)).then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
            Map<String, String> statuses = resourceStatuses(stackName);
            assertEquals("UPDATE_FAILED", statuses.get("Role"), statuses.toString());
            assertEquals("UPDATE_FAILED", statuses.get("LogGroup"), statuses.toString());

            cfn("DeleteStack", stackName, null).then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);

            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRole")
                .formParam("RoleName", roleName)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body(containsString("<Code>NoSuchEntity</Code>"));
            logs("DescribeLogGroups", "{\"logGroupNamePrefix\":\"" + logGroup + "\"}").then()
                .statusCode(200)
                .body(not(containsString(logGroup)));
            // The group Dup collided with was never the stack's, so it survives.
            logs("DescribeLogGroups", "{\"logGroupNamePrefix\":\"" + outsideGroup + "\"}").then()
                .statusCode(200)
                .body(containsString(outsideGroup));

            List<Map<String, String>> events = XmlParser.extractGroups(
                    cfn("DescribeStackEvents", stackId, null).then().statusCode(200).extract().asString(),
                    "member");
            assertDeleteComplete(events, "Role", roleName);
            assertDeleteComplete(events, "LogGroup", logGroup);
        } finally {
            // Best effort and unchecked, so a failed assertion above is not masked. The role and log
            // group are deleted directly because a regression would leave them behind DeleteStack.
            cfn("DeleteStack", stackName, null);
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", roleName)
            .when()
                .post("/");
            logs("DeleteLogGroup", "{\"logGroupName\":\"" + logGroup + "\"}");
            logs("DeleteLogGroup", "{\"logGroupName\":\"" + outsideGroup + "\"}");
        }
    }

    private static void assertDeleteComplete(List<Map<String, String>> events, String logicalId,
                                             String physicalId) {
        boolean found = events.stream().anyMatch(event -> logicalId.equals(event.get("LogicalResourceId"))
                && physicalId.equals(event.get("PhysicalResourceId"))
                && "DELETE_COMPLETE".equals(event.get("ResourceStatus")));
        assertTrue(found, "no DELETE_COMPLETE event for " + logicalId + " (" + physicalId + ") in " + events);
    }

    private static Map<String, String> resourceStatuses(String stackName) {
        String body = cfn("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "ResourceStatus");
    }

    /** The role and log group, changed and joined by a colliding log group when outsideGroup is set. */
    private static String template(String roleName, String logGroup, String outsideGroup) {
        String roleExtra = outsideGroup == null ? "" : ", \"Description\": \"updated\"";
        String groupExtra = outsideGroup == null ? "" : ", \"RetentionInDays\": 7";
        String dup = outsideGroup == null ? "" : """
                ,
                "Dup": {
                  "Type": "AWS::Logs::LogGroup",
                  "DependsOn": ["Role", "LogGroup"],
                  "Properties": {"LogGroupName": "%s"}
                }""".formatted(outsideGroup);
        return """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Principal": {"Service": "lambda.amazonaws.com"},
                            "Action": "sts:AssumeRole"
                          }]
                        }%s
                      }
                    },
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "%s"%s}
                    }%s
                  }
                }
                """.formatted(roleName, roleExtra, logGroup, groupExtra, dup);
    }

    private static Response cfn(String action, String stackName, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody)
                .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM");
        }
        return request.when().post("/");
    }

    private static Response logs(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", LOGS_AUTH)
            .header("X-Amz-Target", "Logs_20140328." + target)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when()
            .post("/");
    }
}
