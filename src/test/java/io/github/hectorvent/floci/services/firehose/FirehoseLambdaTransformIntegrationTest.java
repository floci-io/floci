package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.FirehoseRequests.answerTransforms;
import static io.github.hectorvent.floci.testing.FirehoseRequests.encode;
import static io.github.hectorvent.floci.testing.FirehoseRequests.firstKey;
import static io.github.hectorvent.floci.testing.FirehoseRequests.putRecordBatch;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the Lambda transform on the real delivery path: what
 * the function returns is what reaches S3, and what it fails reaches the error
 * output instead. {@link FirehoseLambdaTransformerTest} covers the per-record
 * result semantics against the transformer directly; this class is what proves
 * the flush path runs it at all.
 *
 * The function itself is mocked. Running a real one needs Docker, which a
 * delivery-path test should not depend on, and the invocation payload is
 * asserted here from what the mock received.
 */
@QuarkusTest
@TestProfile(FirehoseLambdaTransformIntegrationTest.FlushEveryPairProfile.class)
class FirehoseLambdaTransformIntegrationTest {

    public static class FlushEveryPairProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Each test puts a batch of two, which then delivers inline.
            return Map.of("floci.services.firehose.flush-record-count", "2");
        }
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:transform";

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theFunctionsOutputIsWhatReachesTheDestination() {
        String stream = "transform-ok-stream";
        String bucket = "transform-ok-archive";
        createDeliveryStream(stream, bucket);
        answerTransforms(lambdaService, (recordId, index) ->
                "{\"recordId\":\"" + recordId + "\",\"result\":\"Ok\",\"data\":\"" + encode("TRANSFORMED-" + index) + "\"}");

        putRecordBatch(stream, "one", "two");

        given()
            .when()
            .get("/" + bucket + "/" + firstKey(bucket, "data/"))
            .then()
            .statusCode(200)
            .body(equalTo("TRANSFORMED-0\nTRANSFORMED-1\n"));
    }

    @Test
    void aFailedRecordGoesToTheErrorOutputWhileTheRestDelivers() {
        String stream = "transform-failed-stream";
        String bucket = "transform-failed-archive";
        createDeliveryStream(stream, bucket);
        answerTransforms(lambdaService, (recordId, index) -> index == 0
                ? "{\"recordId\":\"" + recordId + "\",\"result\":\"ProcessingFailed\"}"
                : "{\"recordId\":\"" + recordId + "\",\"result\":\"Ok\",\"data\":\"" + encode("kept") + "\"}");

        putRecordBatch(stream, "one", "two");

        given()
            .when()
            .get("/" + bucket + "/" + firstKey(bucket, "data/"))
            .then()
            .statusCode(200)
            .body(equalTo("kept\n"));

        String errorLine = given()
            .when()
            .get("/" + bucket + "/" + firstKey(bucket, "errors/processing-failed/"))
            .then()
            .statusCode(200)
            .extract().asString();
        assertTrue(errorLine.contains("\"errorCode\":\"Lambda.ProcessingFailedStatus\""), errorLine);
        assertTrue(errorLine.contains("\"rawData\":\"" + encode("one") + "\""), errorLine);
        assertTrue(errorLine.contains("\"lambdaARN\":\"" + FUNCTION_ARN + "\""), errorLine);
    }

    @Test
    void aBatchTheFunctionDroppedEntirelyDeliversNothing() {
        String stream = "transform-dropped-stream";
        String bucket = "transform-dropped-archive";
        createDeliveryStream(stream, bucket);
        answerTransforms(lambdaService, (recordId, index) ->
                "{\"recordId\":\"" + recordId + "\",\"result\":\"Dropped\"}");

        putRecordBatch(stream, "one", "two");

        String listing = given()
            .when()
            .get("/" + bucket)
            .then()
            .statusCode(200)
            .extract().asString();
        assertFalse(listing.contains("<Key>"), "expected no delivered object, got: " + listing);
    }

    @Test
    void theInvocationCarriesEveryBufferedRecord() {
        String stream = "transform-payload-stream";
        String bucket = "transform-payload-archive";
        createDeliveryStream(stream, bucket);
        List<byte[]> payloads = answerTransforms(lambdaService, (recordId, index) ->
                "{\"recordId\":\"" + recordId + "\",\"result\":\"Dropped\"}");

        putRecordBatch(stream, "one", "two");

        assertEquals(1, payloads.size(), "the whole buffer should be one invocation");
        String payload = new String(payloads.get(0), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"deliveryStreamArn\":\"arn:aws:firehose:"), payload);
        assertTrue(payload.contains("\"data\":\"" + encode("one") + "\""), payload);
        assertTrue(payload.contains("\"data\":\"" + encode("two") + "\""), payload);
    }

    private void createDeliveryStream(String streamName, String bucket) {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "DirectPut",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "arn:aws:iam::000000000000:role/firehose-delivery-role",
                        "BucketARN": "arn:aws:s3:::%s",
                        "Prefix": "data/!{timestamp:yyyy}/",
                        "ErrorOutputPrefix": "errors/!{firehose:error-output-type}/",
                        "ProcessingConfiguration": {
                          "Enabled": true,
                          "Processors": [
                            {
                              "Type": "Lambda",
                              "Parameters": [{"ParameterName": "LambdaArn", "ParameterValue": "%s"}]
                            }
                          ]
                        }
                      }
                    }
                    """.formatted(streamName, bucket, FUNCTION_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }
}
