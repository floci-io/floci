package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Delivery-plane coverage for the S3 destination: records put with
 * PutRecord / PutRecordBatch must land in the destination bucket when either
 * BufferingHints trigger fires — IntervalInSeconds elapsed since the oldest
 * buffered record, or SizeInMBs worth of data buffered, whichever comes first.
 */
@QuarkusTest
class FirehoseS3DeliveryIntegrationTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-delivery-role";
    private static final String JSON_CT = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putRecordIsDeliveredToS3WithinBufferingInterval() {
        String bucket = "fh-delivery-interval-bucket";
        String stream = "fh-delivery-interval-stream";
        createBucket(bucket);
        createStream(stream, bucket, "interval/", 1, 1);

        putRecord(stream, "{\"hello\":\"world\"}");

        String key = awaitFirstKey(bucket, "interval/");

        given().when().get("/" + bucket + "/" + key)
                .then().statusCode(200)
                .body(equalTo("{\"hello\":\"world\"}\n"));
    }

    @Test
    void putRecordBatchIsDeliveredAsOneObjectWithAllRecords() {
        String bucket = "fh-delivery-batch-bucket";
        String stream = "fh-delivery-batch-stream";
        createBucket(bucket);
        createStream(stream, bucket, "batch/", 1, 1);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecordBatch")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "Records": [
                        { "Data": "%s" },
                        { "Data": "%s" },
                        { "Data": "%s" }
                      ]
                    }
                    """.formatted(stream, b64("{\"n\":1}"), b64("{\"n\":2}"), b64("{\"n\":3}")))
        .when().post("/")
        .then().statusCode(200)
            .body("FailedPutCount", equalTo(0));

        String key = awaitFirstKey(bucket, "batch/");

        given().when().get("/" + bucket + "/" + key)
                .then().statusCode(200)
                .body(equalTo("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n"));
    }

    @Test
    void reachingSizeThresholdFlushesWithoutWaitingForInterval() {
        String bucket = "fh-delivery-size-bucket";
        String stream = "fh-delivery-size-stream";
        createBucket(bucket);
        // Interval long enough (900s) that only the 1 MiB size trigger can
        // explain a delivery inside the test's await window.
        createStream(stream, bucket, "size/", 1, 900);

        String halfMiB = "x".repeat(600 * 1024);
        putRecord(stream, halfMiB);
        putRecord(stream, halfMiB);

        String key = awaitFirstKey(bucket, "size/");

        given().when().get("/" + bucket + "/" + key)
                .then().statusCode(200)
                .header("Content-Length", String.valueOf(2 * (600 * 1024 + 1)));
    }

    @Test
    void bufferedRecordsAreDroppedWhenStreamIsDeleted() throws InterruptedException {
        String bucket = "fh-delivery-deleted-bucket";
        String stream = "fh-delivery-deleted-stream";
        createBucket(bucket);
        // Interval comfortably larger than the put→delete gap so a slow CI
        // machine can't let the flusher fire before the delete lands.
        createStream(stream, bucket, "deleted/", 1, 3);

        putRecord(stream, "{\"orphan\":true}");

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + stream + "\" }")
        .when().post("/")
        .then().statusCode(200);

        // Past the 3s interval (plus flusher tick) nothing may have been delivered.
        Thread.sleep(5_000);
        given().when().get("/" + bucket + "?prefix=deleted/")
                .then().statusCode(200)
                .body(not(containsString("<Contents>")));
    }

    @Test
    void createRejectsDuplicateStreamName() {
        String bucket = "fh-delivery-dup-bucket";
        String stream = "fh-delivery-dup-stream";
        createBucket(bucket);
        createStream(stream, bucket, "dup/", 1, 300);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "arn:aws:s3:::%s"
                      }
                    }
                    """.formatted(stream, ROLE_ARN, bucket))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void binaryRecordDataIsDeliveredByteIdentical() {
        String bucket = "fh-delivery-binary-bucket";
        String stream = "fh-delivery-binary-stream";
        createBucket(bucket);
        createStream(stream, bucket, "binary/", 1, 1);

        // Not valid UTF-8: a String round-trip would mangle these bytes.
        byte[] payload = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE, 0x01};
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecord")
            .body("{ \"DeliveryStreamName\": \"%s\", \"Record\": { \"Data\": \"%s\" } }"
                    .formatted(stream, Base64.getEncoder().encodeToString(payload)))
        .when().post("/")
        .then().statusCode(200);

        String key = awaitFirstKey(bucket, "binary/");

        byte[] delivered = given().when().get("/" + bucket + "/" + key)
                .then().statusCode(200)
                .extract().asByteArray();
        byte[] expected = new byte[payload.length + 1];
        System.arraycopy(payload, 0, expected, 0, payload.length);
        expected[payload.length] = '\n';
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, delivered);
    }

    @Test
    void createRejectsOutOfRangeBufferingHints() {
        rejectHints(0, 300, "sizeInMBs");
        rejectHints(129, 300, "sizeInMBs");
        rejectHints(5, -1, "intervalInSeconds");
        rejectHints(5, 901, "intervalInSeconds");
    }

    private void rejectHints(int sizeInMBs, int intervalInSeconds, String expectedMember) {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "fh-invalid-hints-stream",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "arn:aws:s3:::fh-invalid-hints-bucket",
                        "BufferingHints": { "SizeInMBs": %d, "IntervalInSeconds": %d }
                      }
                    }
                    """.formatted(ROLE_ARN, sizeInMBs, intervalInSeconds))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString(expectedMember));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static void createBucket(String bucket) {
        given().when().put("/" + bucket).then().statusCode(200);
    }

    private static void createStream(String stream, String bucket, String prefix,
                                     int sizeInMBs, int intervalInSeconds) {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "DirectPut",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "arn:aws:s3:::%s",
                        "Prefix": "%s",
                        "BufferingHints": { "SizeInMBs": %d, "IntervalInSeconds": %d }
                      }
                    }
                    """.formatted(stream, ROLE_ARN, bucket, prefix, sizeInMBs, intervalInSeconds))
        .when().post("/")
        .then().statusCode(200);
    }

    private static void putRecord(String stream, String data) {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecord")
            .body("{ \"DeliveryStreamName\": \"%s\", \"Record\": { \"Data\": \"%s\" } }"
                    .formatted(stream, b64(data)))
        .when().post("/")
        .then().statusCode(200);
    }

    /** Polls the bucket listing until an object appears under the prefix and returns its key. */
    private static String awaitFirstKey(String bucket, String prefix) {
        return await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> given().when().get("/" + bucket + "?prefix=" + prefix)
                                .then().statusCode(200)
                                .extract().xmlPath().getString("ListBucketResult.Contents[0].Key"),
                        key -> key != null && !key.isEmpty());
    }

    private static String b64(String data) {
        return Base64.getEncoder().encodeToString(data.getBytes());
    }
}
