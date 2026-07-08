package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseExtendedS3IntegrationTest {

    private static final String STREAM_NAME = "test-extended-s3-stream";
    private static final String LEGACY_STREAM_NAME = "test-legacy-s3-stream";
    private static final String MINIMAL_STREAM_NAME = "test-minimal-extended-s3-stream";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-delivery-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::extended-s3-archive";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createWithExtendedS3Configuration() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "DirectPut",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "Prefix": "events/data/",
                        "ErrorOutputPrefix": "events/errors/",
                        "CompressionFormat": "GZIP",
                        "BufferingHints": { "SizeInMBs": 64, "IntervalInSeconds": 120 }
                      }
                    }
                    """.formatted(STREAM_NAME, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    @Test
    @Order(2)
    void describeReturnsExtendedS3Description() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamType", equalTo("DirectPut"))
            .body("DeliveryStreamDescription.VersionId", equalTo("1"))
            .body("DeliveryStreamDescription.HasMoreDestinations", equalTo(false))
            .body("DeliveryStreamDescription.Destinations[0].DestinationId", notNullValue())
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.RoleARN", equalTo(ROLE_ARN))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BucketARN", equalTo(BUCKET_ARN))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.Prefix", equalTo("events/data/"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.ErrorOutputPrefix", equalTo("events/errors/"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat", equalTo("GZIP"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.SizeInMBs", equalTo(64))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.IntervalInSeconds", equalTo(120))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.EncryptionConfiguration.NoEncryptionConfig", equalTo("NoEncryption"))
            .body("DeliveryStreamDescription.Destinations[0].S3DestinationDescription.BucketARN", equalTo(BUCKET_ARN))
            .body("DeliveryStreamDescription.Destinations[0].S3DestinationDescription.RoleARN", equalTo(ROLE_ARN));
    }

    @Test
    @Order(3)
    void describeAppliesDefaultsForMinimalConfig() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      }
                    }
                    """.formatted(MINIMAL_STREAM_NAME, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + MINIMAL_STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat", equalTo("UNCOMPRESSED"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.SizeInMBs", equalTo(5))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.IntervalInSeconds", equalTo(300))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.EncryptionConfiguration.NoEncryptionConfig", equalTo("NoEncryption"));
    }

    @Test
    @Order(4)
    void legacyS3ConfigurationAlsoReturnsExtendedDescription() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "S3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "Prefix": "legacy/"
                      }
                    }
                    """.formatted(LEGACY_STREAM_NAME, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + LEGACY_STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.Destinations[0].S3DestinationDescription.BucketARN", equalTo(BUCKET_ARN))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BucketARN", equalTo(BUCKET_ARN))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.Prefix", equalTo("legacy/"));
    }

    @Test
    @Order(5)
    void updateDestinationRejectsStaleVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "99",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": { "CompressionFormat": "Snappy" }
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConcurrentModificationException"));
    }

    @Test
    @Order(6)
    void updateDestinationAppliesChangesAndBumpsVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "1",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": {
                        "CompressionFormat": "Snappy",
                        "BufferingHints": { "SizeInMBs": 128, "IntervalInSeconds": 60 }
                      }
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.VersionId", equalTo("2"))
            .body("DeliveryStreamDescription.LastUpdateTimestamp", notNullValue())
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat", equalTo("Snappy"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.SizeInMBs", equalTo(128))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.RoleARN", equalTo(ROLE_ARN))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.Prefix", equalTo("events/data/"));
    }

    @Test
    @Order(7)
    void updateDestinationWithoutUpdatePayloadIsRejectedAndDoesNotBumpVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "2",
                      "DestinationId": "destinationId-000000000001"
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.VersionId", equalTo("2"));
    }

    @Test
    @Order(8)
    void updateDestinationRejectsPartialBufferingHintsAndDoesNotBumpVersion() {
        // AWS requires SizeInMBs and IntervalInSeconds to be specified together
        // (firehose service-2.json BufferingHints member docs).
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "2",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": {
                        "BufferingHints": { "SizeInMBs": 16 }
                      }
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.VersionId", equalTo("2"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.SizeInMBs", equalTo(128))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.BufferingHints.IntervalInSeconds", equalTo(60));
    }

    @Test
    @Order(9)
    void createDeliveryStreamRejectsPartialBufferingHints() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "partial-hints-stream",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "BufferingHints": { "IntervalInSeconds": 60 }
                      }
                    }
                    """.formatted(ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(10)
    void updateDestinationRejectsUnknownDestinationId() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "2",
                      "DestinationId": "destinationId-999999999999",
                      "ExtendedS3DestinationUpdate": { "CompressionFormat": "GZIP" }
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }
}
