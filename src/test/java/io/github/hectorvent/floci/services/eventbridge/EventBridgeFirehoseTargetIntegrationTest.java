package io.github.hectorvent.floci.services.eventbridge;

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
import static org.hamcrest.Matchers.equalTo;

/**
 * End to end EventBridge → Firehose target delivery: rule matches a PutEvents event,
 * the invoker puts the event JSON as a Firehose record, and the stream's S3 flush
 * lands it in the destination bucket.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeFirehoseTargetIntegrationTest {

    private static final String BUCKET = "eb-firehose-target-bucket";
    private static final String STREAM = "eb-firehose-target-stream";
    private static final String RULE = "eb-firehose-target-rule";
    private static final String JSON_CT = "application/x-amz-json-1.1";

    @Inject
    FirehoseService firehoseService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupBucketStreamAndRule() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "arn:aws:iam::000000000000:role/firehose-delivery-role",
                        "BucketARN": "arn:aws:s3:::%s",
                        "Prefix": "events/"
                      }
                    }
                    """.formatted(STREAM, BUCKET))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                    {
                      "Name": "%s",
                      "EventPattern": "{\\"source\\": [\\"local.orders\\"]}"
                    }
                    """.formatted(RULE))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutTargets")
            .body("""
                    {
                      "Rule": "%s",
                      "Targets": [{
                        "Id": "firehose-target",
                        "Arn": "arn:aws:firehose:us-east-1:000000000000:deliverystream/%s"
                      }]
                    }
                    """.formatted(RULE, STREAM))
        .when().post("/")
        .then().statusCode(200)
            .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(2)
    void putEventIsDeliveredToS3ThroughFirehose() {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                    {
                      "Entries": [{
                        "Source": "local.orders",
                        "DetailType": "OrderPlaced",
                        "Detail": "{\\"orderId\\": \\"order-eb-fh-1\\"}"
                      }]
                    }
                    """)
        .when().post("/")
        .then().statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        // The record sits in the stream buffer until size/interval flush; force it so
        // the assertion is deterministic (same mechanism the scheduled flusher uses).
        firehoseService.flush(STREAM);

        String key = given().when().get("/" + BUCKET + "?prefix=events/")
                .then().statusCode(200)
                .extract().xmlPath().getString("ListBucketResult.Contents[0].Key");

        given().when().get("/" + BUCKET + "/" + key)
                .then().statusCode(200)
                .body(containsString("order-eb-fh-1"))
                .body(containsString("\"detail-type\":\"OrderPlaced\""));
    }
}
