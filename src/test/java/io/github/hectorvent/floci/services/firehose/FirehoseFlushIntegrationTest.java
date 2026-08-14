package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level coverage of the AWS-faithful BufferingHints flush triggers:
 * volume ({@code SizeInMBs}) and time ({@code IntervalInSeconds}), delivered
 * by the background flusher without any explicit flush call.
 *
 * The emulator-only record-count trigger is covered by
 * {@link FirehoseFlushRecordCountIntegrationTest}: it needs a different
 * config override, and a Quarkus test profile applies to the whole class.
 */
@QuarkusTest
@TestProfile(FirehoseFlushIntegrationTest.FastTickProfile.class)
class FirehoseFlushIntegrationTest {

    public static class FastTickProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // 1s flush tick so the interval test waits seconds, not the 10s default.
            return Map.of("floci.services.firehose.tick-interval-seconds", "1");
        }
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final Pattern KEY_PATTERN = Pattern.compile("<Key>([^<]+)</Key>");

    /** The global test-scope Clock alternative, frozen at 2026-01-01 unless advanced. */
    @Inject
    MutableClock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sizeTriggerDeliversWhenBufferedBytesReachSizeInMBs() {
        String stream = "flush-size-stream";
        String bucket = "flush-size-archive";
        createDeliveryStream(stream, bucket, 1, 300);

        String halfMiB = Base64.getEncoder()
                .encodeToString("A".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8));
        putRecord(stream, halfMiB);
        putRecord(stream, halfMiB); // crosses the 1 MiB threshold: flushes inline, no waiting

        String body = given()
            .when()
            .get("/" + bucket + "/" + firstDeliveredKey(bucket))
            .then()
            .statusCode(200)
            .extract().asString();
        // Both records, each followed by the newline the flush appends.
        assertEquals(2 * 512 * 1024 + 2, body.length());
        assertTrue(body.startsWith("A"), "delivered body should contain the record payload");
    }

    @Test
    void intervalTriggerDeliversAfterIntervalInSecondsElapses() {
        String stream = "flush-interval-stream";
        String bucket = "flush-interval-archive";
        createDeliveryStream(stream, bucket, 5, 60);

        putRecord(stream, Base64.getEncoder()
                .encodeToString("{\"n\":42}".getBytes(StandardCharsets.UTF_8)));

        // The test-scope Clock is frozen, so the interval can only elapse by
        // advancing it; the real 1s tick then finds the buffer due.
        clock.advance(Duration.ofSeconds(61));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            given()
                .when()
                .get("/" + bucket + "/" + firstDeliveredKey(bucket))
                .then()
                .statusCode(200)
                .body(equalTo("{\"n\":42}\n")));
    }

    private void createDeliveryStream(String streamName, String bucket, int sizeInMBs, int intervalInSeconds) {
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
                        "BufferingHints": {"SizeInMBs": %d, "IntervalInSeconds": %d}
                      }
                    }
                    """.formatted(streamName, bucket, sizeInMBs, intervalInSeconds))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    private void putRecord(String streamName, String base64Data) {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecord")
            .body("{ \"DeliveryStreamName\": \"" + streamName + "\", \"Record\": {\"Data\": \"" + base64Data + "\"} }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RecordId", notNullValue());
    }

    private String firstDeliveredKey(String bucket) {
        String listing = given()
            .when()
            .get("/" + bucket)
            .then()
            .statusCode(200)
            .extract().asString();
        Matcher key = KEY_PATTERN.matcher(listing);
        assertTrue(key.find(), "expected a delivered object in " + bucket + ", got: " + listing);
        return key.group(1);
    }
}
