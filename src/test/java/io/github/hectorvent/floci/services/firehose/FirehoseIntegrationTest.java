package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseIntegrationTest {

    private static final String STREAM_NAME = "test-delivery-stream";
    private static final String KINESIS_SOURCE_STREAM_NAME = "kinesis-source-delivery-stream";
    private static final String KINESIS_STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/events";
    private static final String SOURCE_ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    @Test
    @Order(2)
    void describeDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamName", equalTo(STREAM_NAME));
    }

    @Test
    @Order(3)
    void tagAndUntagDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.TagDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\", \"Tags\": [ { \"Key\": \"env\", \"Value\": \"prod\" }, { \"Key\": \"owner\", \"Value\": \"team-a\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.ListTagsForDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("prod"))
            .body("Tags.find { it.Key == 'owner' }.Value", equalTo("team-a"))
            .body("HasMoreTags", equalTo(false));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.UntagDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\", \"TagKeys\": [ \"env\" ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.ListTagsForDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"))
            .body("Tags[0].Value", equalTo("team-a"));
    }

    @Test
    @Order(4)
    void deleteDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void describeDeletedDeliveryStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(6)
    void startAndStopDeliveryStreamEncryption() {
        String encryptionStream = "encryption-stream-" + System.currentTimeMillis();
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String keyArn = "arn:aws:kms:us-east-1:123456789012:key/test-key";
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.StartDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream
                    + "\", \"DeliveryStreamEncryptionConfigurationInput\": { \"KeyType\": \"CUSTOMER_MANAGED_CMK\", \"KeyARN\": \""
                    + keyArn + "\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType",
                    equalTo("CUSTOMER_MANAGED_CMK"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyARN",
                    equalTo(keyArn))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status",
                    equalTo("ENABLED"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.StopDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status",
                    equalTo("DISABLED"))
            // Disabling must not erase the key identity: real AWS keeps reporting the
            // stopped stream's KeyType and customer KeyARN.
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType",
                    equalTo("CUSTOMER_MANAGED_CMK"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyARN",
                    equalTo("arn:aws:kms:us-east-1:123456789012:key/test-key"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void startDeliveryStreamEncryptionWithoutInputDefaultsToAwsOwnedCmk() {
        // DeliveryStreamEncryptionConfigurationInput is optional (Required: No) - omitting
        // it is the documented way to enable SSE with the service-owned key, not an error.
        String encryptionStream = "encryption-noinput-" + System.currentTimeMillis();
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.StartDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status",
                    equalTo("ENABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType",
                    equalTo("AWS_OWNED_CMK"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void startDeliveryStreamEncryptionWithoutCustomerKeyReturnsInvalidArgument() {
        String encryptionStream = "encryption-validation-" + System.currentTimeMillis();
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.StartDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream
                    + "\", \"DeliveryStreamEncryptionConfigurationInput\": { \"KeyType\": \"CUSTOMER_MANAGED_CMK\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    void startDeliveryStreamEncryptionWithMalformedKeyArnReturnsInvalidArgument() {
        String encryptionStream = "encryption-badarn-" + System.currentTimeMillis();
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // botocore pins AWSKMSKeyARNForSSE to
        // arn:.*:kms:<region>:<12-digit account>:key/<id> — a bare key id is not a KeyARN,
        // and storing it would make DescribeDeliveryStream report a key that cannot exist.
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.StartDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream
                    + "\", \"DeliveryStreamEncryptionConfigurationInput\": "
                    + "{ \"KeyType\": \"CUSTOMER_MANAGED_CMK\", \"KeyARN\": \"not-an-arn\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        // The rejected call must not have enabled encryption on the stream.
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status",
                    equalTo("DISABLED"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + encryptionStream + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(16)
    void describeDeliveryStreamReturnsKinesisSourceConfiguration() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("""
                {
                  "DeliveryStreamName": "%s",
                  "DeliveryStreamType": "KinesisStreamAsSource",
                  "KinesisStreamSourceConfiguration": {
                    "KinesisStreamARN": "%s",
                    "RoleARN": "%s"
                  }
                }
                """.formatted(KINESIS_SOURCE_STREAM_NAME, KINESIS_STREAM_ARN, SOURCE_ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + KINESIS_SOURCE_STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamType", equalTo("KinesisStreamAsSource"))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.KinesisStreamARN", equalTo(KINESIS_STREAM_ARN))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.RoleARN", equalTo(SOURCE_ROLE_ARN))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.DeliveryStartTimestamp", notNullValue());
    }

    @Test
    @Order(10)
    void createDuplicateDeliveryStreamReturnsResourceInUse() {
        // Create first stream
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"duplicate-stream-test\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());

        // Attempt to create duplicate → should fail with ResourceInUseException
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"duplicate-stream-test\" }")
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceInUseException"))
            .body("message", containsString("already exists"));

        // Cleanup
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"duplicate-stream-test\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void deleteNonExistentDeliveryStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"non-existent-stream\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void putRecordToNonExistentStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.PutRecord")
            .body("{ \"DeliveryStreamName\": \"non-existent-stream\", \"Record\": { \"Data\": \"dGVzdA==\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(13)
    void tagNonExistentStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.TagDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"non-existent-stream\", \"Tags\": [ { \"Key\": \"env\", \"Value\": \"test\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(14)
    void listTagsForNonExistentStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.ListTagsForDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"non-existent-stream\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void createDeliveryStreamWithInvalidNameReturnsInvalidArgument() {
        // Test omitted DeliveryStreamName ({})
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        // Test name with spaces
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"my stream\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        // Test name with invalid character
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"stream$name\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        // Test name too long (65 characters)
        String longName = "a".repeat(65);
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + longName + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }
}
