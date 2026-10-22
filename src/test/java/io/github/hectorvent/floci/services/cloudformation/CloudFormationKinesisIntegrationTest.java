package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
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
 * End-to-end check that CloudFormation provisions an AWS::Kinesis::Stream for real (into
 * KinesisService) rather than stubbing it. Streams are metadata (no container), so the test is
 * Docker-free.
 */
@QuarkusTest
class CloudFormationKinesisIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String KINESIS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/kinesis/aws4_request";

    @Test
    void createStackProvisionsStreamVisibleToKinesis() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String streamName = "cfn-stream-" + suffix;
        String stackName = "cfn-kinesis-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::Kinesis::Stream",
                      "Properties": {
                        "Name": "%s",
                        "ShardCount": 2,
                        "RetentionPeriodHours": 48
                      }
                    }
                  },
                  "Outputs": {
                    "StreamArn": {"Value": {"Fn::GetAtt": ["Stream", "Arn"]}}
                  }
                }
                """.formatted(streamName);

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

        // Stack completes and Fn::GetAtt(Stream, Arn) resolves to the real stream ARN.
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
            .body(containsString(":stream/" + streamName));

        // The stream really exists in Kinesis.
        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType("application/x-amz-json-1.1")
            .body("{\"StreamName\":\"" + streamName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(streamName))
            .body(containsString("\"RetentionPeriodHours\":48"));
    }

    @Test
    void updateStackReconcilesExistingStreamInsteadOfFailing() {
        // provision() re-runs on every UpdateStack for every resource regardless of whether its
        // properties changed, so a fixed-name stream left unchanged between deploys used to call
        // CreateStream again and roll back with "Stream already exists" (same family of bug as
        // lex00/floci#16).
        String suffix = Long.toString(System.nanoTime(), 36);
        String streamName = "cfn-stream-update-" + suffix;
        String stackName = "cfn-kinesis-update-stack-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", streamTemplate(streamName, 48))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", streamTemplate(streamName, 72))
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

        // The retention change was reconciled in place under the same stream name.
        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType("application/x-amz-json-1.1")
            .body("{\"StreamName\":\"" + streamName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(streamName))
            .body(containsString("\"RetentionPeriodHours\":72"));
    }

    /** ShardCount updates with no interruption, so an UpdateStack rescales the stream in place. */
    @Test
    void updateStackRescalesTheStreamToTheNewShardCount() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String streamName = "cfn-stream-rescale-" + suffix;
        String stackName = "cfn-kinesis-rescale-stack-" + suffix;

        cloudFormation("CreateStack", stackName, streamTemplate(streamName, 24, 1));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName, CFN_AUTH).status());

        cloudFormation("UpdateStack", stackName, streamTemplate(streamName, 24, 2));
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType("application/x-amz-json-1.1")
            .body("{\"StreamName\":\"" + streamName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("\"OpenShardCount\":2"));
    }

    private static void cloudFormation(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String streamTemplate(String streamName, int retentionPeriodHours) {
        return streamTemplate(streamName, retentionPeriodHours, 2);
    }

    private static String streamTemplate(String streamName, int retentionPeriodHours, int shardCount) {
        return """
                {
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::Kinesis::Stream",
                      "Properties": {
                        "Name": "%s",
                        "ShardCount": %d,
                        "RetentionPeriodHours": %d
                      }
                    }
                  },
                  "Outputs": {
                    "StreamArn": {"Value": {"Fn::GetAtt": ["Stream", "Arn"]}}
                  }
                }
                """.formatted(streamName, shardCount, retentionPeriodHours);
    }
}
