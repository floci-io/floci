package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

public final class FirehoseRequests {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final Pattern RECORD_ID_PATTERN = Pattern.compile("\"recordId\":\"([^\"]+)\"");
    private static final Pattern KEY_PATTERN = Pattern.compile("<Key>([^<]+)</Key>");

    private FirehoseRequests() {}

    public static String encode(String body) {
        return Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    public static void putRecordBatch(String streamName, String... bodies) {
        List<String> records = new ArrayList<>();
        for (String body : bodies) {
            records.add("{\"Data\": \"" + encode(body) + "\"}");
        }
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Firehose_20150804.PutRecordBatch")
                .body("{\"DeliveryStreamName\": \"" + streamName + "\", \"Records\": ["
                        + String.join(",", records) + "]}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("FailedPutCount", equalTo(0));
    }

    public static String firstKey(String bucket, String prefix) {
        String listing = given()
                .when().get("/" + bucket)
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

    /**
     * Answers each transform invocation from the record ids it actually carries, which the caller
     * cannot name: they are minted per batch. Returns the payloads the mock received.
     */
    public static List<byte[]> answerTransforms(LambdaService lambdaService, RecordResponder responder) {
        List<byte[]> payloads = new ArrayList<>();
        Mockito.when(lambdaService.invokeArn(anyString(), any(), any())).thenAnswer(invocation -> {
            byte[] payload = invocation.getArgument(1);
            payloads.add(payload);
            List<String> entries = new ArrayList<>();
            Matcher recordIds = RECORD_ID_PATTERN.matcher(new String(payload, StandardCharsets.UTF_8));
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
    public interface RecordResponder {
        String respond(String recordId, int index);
    }
}
