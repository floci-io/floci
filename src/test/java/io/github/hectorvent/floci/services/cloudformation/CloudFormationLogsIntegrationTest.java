package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
}
