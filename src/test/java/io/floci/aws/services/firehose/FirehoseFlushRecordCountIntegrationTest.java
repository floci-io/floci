package io.floci.aws.services.firehose;

import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code floci.services.firehose.flush-record-count=1} delivers
 * every record to the S3 destination immediately.
 *
 * Kept separate from {@link FirehoseFlushIntegrationTest} (size and interval
 * triggers) because this class needs a different config override and a
 * Quarkus test profile applies to the whole class. The override comes from
 * {@link IndependentServiceSettingsProfile}, which this class shares with other
 * classes that each call a different service, so it deletes its delivery
 * stream and the bucket Firehose created for it.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseFlushRecordCountIntegrationTest {

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

    /**
     * Runs last, since the tests above share the stream: deletes the delivery stream, the record it
     * delivered and the bucket Firehose created for it.
     */
    @Test
    @Order(3)
    void cleanupDeletesTheStreamAndBucketItCreated() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String listing = given()
            .when()
            .get("/" + BUCKET)
            .then()
            .statusCode(200)
            .extract().asString();
        Matcher key = Pattern.compile("<Key>([^<]+)</Key>").matcher(listing);
        while (key.find()) {
            given().when().delete("/" + BUCKET + "/" + key.group(1)).then().statusCode(204);
        }
        given().when().delete("/" + BUCKET).then().statusCode(204);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamNames", not(hasItem(STREAM_NAME)));
        given().when().head("/" + BUCKET).then().statusCode(404);
    }
}
