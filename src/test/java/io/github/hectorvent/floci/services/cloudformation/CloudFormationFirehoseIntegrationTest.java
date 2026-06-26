package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation provisions an AWS::KinesisFirehose::DeliveryStream for real
 * (into FirehoseService) rather than stubbing it. Delivery streams are metadata (no container), so
 * the test is Docker-free.
 */
@QuarkusTest
class CloudFormationFirehoseIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String FIREHOSE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/firehose/aws4_request";

    @Test
    void createStackProvisionsDeliveryStreamVisibleToFirehose() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String streamName = "cfn-firehose-" + suffix;
        String stackName = "cfn-firehose-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::KinesisFirehose::DeliveryStream",
                      "Properties": {
                        "DeliveryStreamName": "%s",
                        "DeliveryStreamType": "DirectPut",
                        "S3DestinationConfiguration": {
                          "BucketARN": "arn:aws:s3:::my-firehose-bucket",
                          "RoleARN": "arn:aws:iam::000000000000:role/firehose-role",
                          "Prefix": "logs/"
                        }
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

        // Stack completes and Fn::GetAtt(Stream, Arn) resolves to the real delivery stream ARN.
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
            .body(containsString(":deliverystream/" + streamName));

        // The delivery stream really exists in Firehose.
        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", FIREHOSE_AUTH)
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .contentType("application/x-amz-json-1.1")
            .body("{\"DeliveryStreamName\":\"" + streamName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(streamName));
    }
}
