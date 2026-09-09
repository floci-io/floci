package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code floci.services.firehose.flush-record-count=1} delivers
 * every record to the S3 destination immediately.
 *
 * Kept separate from {@link FirehoseFlushIntegrationTest} (size and interval
 * triggers) because this class needs a different config override and a
 * Quarkus test profile applies to the whole class.
 */
@QuarkusTest
@TestProfile(FirehoseFlushRecordCountIntegrationTest.FlushEveryRecordProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseFlushRecordCountIntegrationTest {

    public static class FlushEveryRecordProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.firehose.flush-record-count", "1");
        }
    }

    private static final String STREAM_NAME = "test-flush-count-stream";
    private static final String BUCKET = "flush-count-archive";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDeliveryStream() {
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
                        "Prefix": "single/"
                      }
                    }
                    """.formatted(STREAM_NAME, BUCKET))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    @Test
    @Order(2)
    void singleRecordIsDeliveredImmediately() {
        // {"n":1} — base64: eyJuIjoxfQ==
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecord")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\", \"Record\": {\"Data\": \"eyJuIjoxfQ==\"} }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RecordId", notNullValue());

        // The record must be visible in the destination bucket without any
        // further puts (path-style S3 ListObjects on the same edge port).
        String listing = given()
            .when()
            .get("/" + BUCKET)
            .then()
            .statusCode(200)
            .extract().asString();

        Matcher key = Pattern.compile("<Key>(single/[^<]+)</Key>").matcher(listing);
        assertTrue(key.find(), "expected a delivered object under single/, got: " + listing);

        given()
            .when()
            .get("/" + BUCKET + "/" + key.group(1))
        .then()
            .statusCode(200)
            .body(equalTo("{\"n\":1}\n"));
    }
}
