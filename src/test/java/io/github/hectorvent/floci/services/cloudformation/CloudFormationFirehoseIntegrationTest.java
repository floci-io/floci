package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hectorvent.floci.testing.FirehoseRequests.answerTransforms;
import static io.github.hectorvent.floci.testing.FirehoseRequests.encode;
import static io.github.hectorvent.floci.testing.FirehoseRequests.firstKey;
import static io.github.hectorvent.floci.testing.FirehoseRequests.putRecordBatch;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * End-to-end check that CloudFormation provisions an AWS::KinesisFirehose::DeliveryStream for real
 * (into FirehoseService) rather than stubbing it. Delivery streams are metadata (no container), so
 * the test is Docker-free; the Lambda processor is mocked for the same reason.
 */
@QuarkusTest
class CloudFormationFirehoseIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String FIREHOSE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/firehose/aws4_request";
    private static final String FIREHOSE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String EXTENDED_DESTINATION =
            "DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.";
    private static final String LAMBDA_ARN = EXTENDED_DESTINATION
            + "ProcessingConfiguration.Processors[0].Parameters.find { it.ParameterName == 'LambdaArn' }.ParameterValue";

    @InjectMock
    LambdaService lambdaService;

    @Inject
    FirehoseService firehoseService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

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
                          "Prefix": "logs/",
                          "CompressionFormat": "GZIP"
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
            .body(containsString(streamName))
            .body(containsString("GZIP"));
    }

    @Test
    void aStackStreamRunsItsLambdaProcessorAndReadsBackItsLogging() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String streamName = "cfn-firehose-transform-" + suffix;
        String stackName = "cfn-firehose-transform-stack-" + suffix;
        String bucket = "cfn-firehose-transform-" + suffix;
        String logGroup = "/aws/kinesisfirehose/" + suffix;
        String firstFunction = "transform-" + suffix;
        String secondFunction = "transform-next-" + suffix;
        String firstArn = "arn:aws:lambda:us-east-1:000000000000:function:" + firstFunction;
        String secondArn = "arn:aws:lambda:us-east-1:000000000000:function:" + secondFunction;

        stackRequest("CreateStack", stackName, transformTemplate(suffix, streamName, bucket, firstFunction));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        describeDeliveryStream(streamName)
            .body(EXTENDED_DESTINATION + "RoleARN", equalTo("arn:aws:iam::000000000000:role/firehose-delivery"))
            .body(EXTENDED_DESTINATION + "ErrorOutputPrefix", equalTo("errors/!{firehose:error-output-type}/"))
            .body(EXTENDED_DESTINATION + "CloudWatchLoggingOptions.Enabled", equalTo(true))
            .body(EXTENDED_DESTINATION + "CloudWatchLoggingOptions.LogGroupName", equalTo(logGroup))
            .body(EXTENDED_DESTINATION + "CloudWatchLoggingOptions.LogStreamName", equalTo("DestinationDelivery"))
            .body(EXTENDED_DESTINATION + "ProcessingConfiguration.Enabled", equalTo(true))
            .body(LAMBDA_ARN, equalTo(firstArn))
            .body("DeliveryStreamDescription.Destinations[0].S3DestinationDescription"
                    + ".CloudWatchLoggingOptions.LogGroupName", equalTo(logGroup));

        List<byte[]> payloads = answerTransforms(lambdaService, (recordId, index) ->
                "{\"recordId\":\"" + recordId + "\",\"result\":\"Ok\",\"data\":\"" + encode("TRANSFORMED-" + index) + "\"}");
        putRecordBatch(streamName, "one", "two");
        firehoseService.flush(streamName);

        given()
        .when()
            .get("/" + bucket + "/" + firstKey(bucket, "data/"))
        .then()
            .statusCode(200)
            .body(equalTo("TRANSFORMED-0\nTRANSFORMED-1\n"));
        assertEquals(1, payloads.size());
        verify(lambdaService).invokeArn(eq(firstArn), any(), any());

        stackRequest("UpdateStack", stackName, transformTemplate(suffix, streamName, bucket, secondFunction));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        describeDeliveryStream(streamName)
            .body(LAMBDA_ARN, equalTo(secondArn))
            .body(EXTENDED_DESTINATION + "CloudWatchLoggingOptions.LogGroupName", equalTo(logGroup));
    }

    private static String transformTemplate(String suffix, String streamName, String bucket, String function) {
        return """
                {
                  "Resources": {
                    "Logs": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "/aws/kinesisfirehose/%s"}
                    },
                    "Stream": {
                      "Type": "AWS::KinesisFirehose::DeliveryStream",
                      "Properties": {
                        "DeliveryStreamName": "%s",
                        "DeliveryStreamType": "DirectPut",
                        "ExtendedS3DestinationConfiguration": {
                          "BucketARN": "arn:aws:s3:::%s",
                          "RoleARN": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/firehose-delivery"},
                          "Prefix": "data/",
                          "ErrorOutputPrefix": {"Fn::Join": ["", ["errors/", "!{firehose:error-output-type}/"]]},
                          "CloudWatchLoggingOptions": {
                            "Enabled": true,
                            "LogGroupName": {"Ref": "Logs"},
                            "LogStreamName": "DestinationDelivery"
                          },
                          "ProcessingConfiguration": {
                            "Enabled": true,
                            "Processors": [
                              {
                                "Type": "Lambda",
                                "Parameters": [
                                  {
                                    "ParameterName": "LambdaArn",
                                    "ParameterValue": {"Fn::Sub": "arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:%s"}
                                  }
                                ]
                              }
                            ]
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(suffix, streamName, bucket, function);
    }

    private static void stackRequest(String action, String stackName, String template) {
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

    private static ValidatableResponse describeDeliveryStream(String streamName) {
        return given()
            .header("Authorization", FIREHOSE_AUTH)
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .contentType(FIREHOSE_CONTENT_TYPE)
            .body("{\"DeliveryStreamName\":\"" + streamName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
