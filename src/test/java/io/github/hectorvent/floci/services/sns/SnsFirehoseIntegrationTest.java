package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnsFirehoseIntegrationTest {

    private static final String FIREHOSE_JSON_CT = "application/x-amz-json-1.1";
    private static final String ENVELOPE_BUCKET = "sns-fh-envelope-bucket";
    private static final String ENVELOPE_STREAM = "sns-fh-envelope-stream";
    private static final String RAW_BUCKET = "sns-fh-raw-bucket";
    private static final String RAW_STREAM = "sns-fh-raw-stream";

    private static String topicArn;
    private static String envelopeSubArn;
    private static String rawSubArn;

    @Inject
    FirehoseService firehoseService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupBucketsAndStreams() {
        // Create destination S3 buckets
        given().when().put("/" + ENVELOPE_BUCKET).then().statusCode(200);
        given().when().put("/" + RAW_BUCKET).then().statusCode(200);

        // Create Firehose delivery streams
        given()
            .contentType(FIREHOSE_JSON_CT)
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "arn:aws:iam::000000000000:role/firehose-role",
                        "BucketARN": "arn:aws:s3:::%s",
                        "Prefix": "envelope/"
                      }
                    }
                    """.formatted(ENVELOPE_STREAM, ENVELOPE_BUCKET))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(FIREHOSE_JSON_CT)
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "arn:aws:iam::000000000000:role/firehose-role",
                        "BucketARN": "arn:aws:s3:::%s",
                        "Prefix": "raw/"
                      }
                    }
                    """.formatted(RAW_STREAM, RAW_BUCKET))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(2)
    void createTopicAndSubscriptions() {
        // Create SNS topic
        topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "sns-to-firehose-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        // Subscribe envelope delivery stream
        String envelopeStreamArn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/" + ENVELOPE_STREAM;
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "firehose")
            .formParam("Endpoint", envelopeStreamArn)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("SubscriptionRoleArn"));

        envelopeSubArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "firehose")
            .formParam("Endpoint", envelopeStreamArn)
            .formParam("Attributes.entry.1.key", "SubscriptionRoleArn")
            .formParam("Attributes.entry.1.value", "arn:aws:iam::000000000000:role/firehose-role")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"))
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetSubscriptionAttributes")
            .formParam("SubscriptionArn", envelopeSubArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SubscriptionRoleArn"))
            .body(containsString("arn:aws:iam::000000000000:role/firehose-role"));

        // Subscribe raw delivery stream
        String rawStreamArn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/" + RAW_STREAM;
        rawSubArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "firehose")
            .formParam("Endpoint", rawStreamArn)
            .formParam("Attributes.entry.1.key", "SubscriptionRoleArn")
            .formParam("Attributes.entry.1.value", "arn:aws:iam::000000000000:role/firehose-role")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"))
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        // Enable RawMessageDelivery on the second subscription
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetSubscriptionAttributes")
            .formParam("SubscriptionArn", rawSubArn)
            .formParam("AttributeName", "RawMessageDelivery")
            .formParam("AttributeValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void publishMessage_deliversToFirehoseSubscriptions() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "{\"event\":\"order_created\",\"id\":12345}")
            .formParam("Subject", "Order Notification")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Flush both firehose streams so buffered records land in S3
        firehoseService.flush(ENVELOPE_STREAM);
        firehoseService.flush(RAW_STREAM);

        // Verify envelope delivery in ENVELOPE_BUCKET
        String envelopeKey = given().when().get("/" + ENVELOPE_BUCKET + "?prefix=envelope/")
                .then().statusCode(200)
                .extract().xmlPath().getString("ListBucketResult.Contents[0].Key");

        given().when().get("/" + ENVELOPE_BUCKET + "/" + envelopeKey)
                .then().statusCode(200)
                .body(containsString("\"Type\":\"Notification\""))
                .body(containsString("\"Subject\":\"Order Notification\""))
                .body(containsString("order_created"))
                .body(containsString("12345"));

        // Verify raw delivery in RAW_BUCKET
        String rawKey = given().when().get("/" + RAW_BUCKET + "?prefix=raw/")
                .then().statusCode(200)
                .extract().xmlPath().getString("ListBucketResult.Contents[0].Key");

        given().when().get("/" + RAW_BUCKET + "/" + rawKey)
                .then().statusCode(200)
                .body(containsString("{\"event\":\"order_created\",\"id\":12345}"))
                .body(not(containsString("\"Type\":\"Notification\"")));
    }
}
