package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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
    private static final Pattern KEY_PATTERN = Pattern.compile("<Key>([^<]+)</Key>");

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
        respondWith((recordId, index) -> "{\"recordId\":\"" + recordId + "\",\"result\":\"Ok\",\"data\":\""
                + encode("TRANSFORMED-" + index) + "\"}");

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
        respondWith((recordId, index) -> index == 0
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
        respondWith((recordId, index) -> "{\"recordId\":\"" + recordId + "\",\"result\":\"Dropped\"}");

        putRecordBatch(stream, "one", "two");

        String listing = given()
            .when()
            .get("/" + bucket)
            .then()
            .statusCode(200)
            .extract().asString();
        assertFalse(KEY_PATTERN.matcher(listing).find(), "expected no delivered object, got: " + listing);
    }

    @Test
    void theInvocationCarriesEveryBufferedRecord() {
        String stream = "transform-payload-stream";
        String bucket = "transform-payload-archive";
        createDeliveryStream(stream, bucket);
        List<byte[]> payloads = respondWith((recordId, index) ->
                "{\"recordId\":\"" + recordId + "\",\"result\":\"Dropped\"}");

        putRecordBatch(stream, "one", "two");

        assertEquals(1, payloads.size(), "the whole buffer should be one invocation");
        String payload = new String(payloads.get(0), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"deliveryStreamArn\":\"arn:aws:firehose:"), payload);
        assertTrue(payload.contains("\"data\":\"" + encode("one") + "\""), payload);
        assertTrue(payload.contains("\"data\":\"" + encode("two") + "\""), payload);
    }

    /**
     * Answers each invocation from the record ids it actually carries, which the caller
     * cannot name: they are minted per batch. Returns the payloads the mock received.
     */
    private List<byte[]> respondWith(RecordResponder responder) {
        List<byte[]> payloads = new ArrayList<>();
        Mockito.when(lambdaService.invokeArn(anyString(), any(), any())).thenAnswer(invocation -> {
            byte[] payload = invocation.getArgument(1);
            payloads.add(payload);
            List<String> entries = new ArrayList<>();
            Matcher recordIds = Pattern.compile("\"recordId\":\"([^\"]+)\"")
                    .matcher(new String(payload, StandardCharsets.UTF_8));
            for (int index = 0; recordIds.find(); index++) {
                entries.add(responder.respond(recordIds.group(1), index));
            }
            InvokeResult result = new InvokeResult();
            result.setStatusCode(200);
            result.setPayload(("{\"records\":[" + String.join(",", entries) + "]}")
                    .getBytes(StandardCharsets.UTF_8));
            return result;
        });
        return payloads;
    }

    @FunctionalInterface
    private interface RecordResponder {
        String respond(String recordId, int index);
    }

    private static String encode(String body) {
        return Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
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

    private void putRecordBatch(String streamName, String... bodies) {
        List<String> records = new ArrayList<>();
        for (String body : bodies) {
            records.add("{\"Data\": \"" + encode(body) + "\"}");
        }
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecordBatch")
            .body("{ \"DeliveryStreamName\": \"" + streamName + "\", \"Records\": ["
                    + String.join(",", records) + "] }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedPutCount", equalTo(0));
    }

    private String firstKey(String bucket, String prefix) {
        String listing = given()
            .when()
            .get("/" + bucket)
            .then()
            .statusCode(200)
            .extract().asString();
        Matcher key = KEY_PATTERN.matcher(listing);
        while (key.find()) {
            if (key.group(1).startsWith(prefix)) {
                return key.group(1);
            }
        }
        throw new AssertionError("expected an object under " + prefix + " in " + bucket + ", got: " + listing);
    }
}
