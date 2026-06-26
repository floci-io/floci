package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
}
