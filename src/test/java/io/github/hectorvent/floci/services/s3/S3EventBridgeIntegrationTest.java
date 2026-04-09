package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3EventBridgeIntegrationTest {

    private static final String EB_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String EB_TARGET = "AWSEvents.";

    private static String ruleArn;
    private static String queueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(EB_CONTENT_TYPE, ContentType.TEXT));
    }

    @Test
    @Order(1)
    void createBucket_forEventBridgeTest() {
        given()
        .when()
            .put("/eb-s3-test-bucket")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createQueue_forEventBridgeDelivery() {
        queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "s3-eb-delivery-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(3)
    void putRule_forS3Events() {
        ruleArn = given()
            .contentType(EB_CONTENT_TYPE)
            .header("X-Amz-Target", EB_TARGET + "PutRule")
            .body("""
                {
                    "Name": "s3-event-rule",
                    "EventPattern": "{\\"source\\":[\\"aws.s3\\"]}",
                    "State": "ENABLED"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleArn", notNullValue())
            .extract().path("RuleArn");
    }

    @Test
    @Order(4)
    void putTarget_sqsForS3Rule() {
        String queueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        given()
            .contentType(EB_CONTENT_TYPE)
            .header("X-Amz-Target", EB_TARGET + "PutTargets")
            .body("""
                {
                    "Rule": "s3-event-rule",
                    "Targets": [{"Id": "1", "Arn": "%s"}]
                }
                """.formatted(queueArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(5)
    void putBucketNotification_enableEventBridge() {
        given()
            .contentType("application/xml")
            .body("""
                <NotificationConfiguration>
                    <EventBridgeConfiguration/>
                </NotificationConfiguration>
                """)
        .when()
            .put("/eb-s3-test-bucket?notification")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void putObject_triggersEventBridgeDelivery() {
        given()
            .contentType("text/plain")
            .body("hello from s3 eventbridge test")
        .when()
            .put("/eb-s3-test-bucket/test-object.txt")
        .then()
            .statusCode(200);

        String messageBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");

        assert messageBody != null : "Expected a message in the queue after S3 put";
        assert messageBody.contains("aws.s3") : "Expected source aws.s3 in: " + messageBody;
        assert messageBody.contains("Object Created") : "Expected detail-type 'Object Created' in: " + messageBody;
        assert messageBody.contains("eb-s3-test-bucket") : "Expected bucket name in: " + messageBody;
        assert messageBody.contains("test-object.txt") : "Expected object key in: " + messageBody;
    }

    @Test
    @Order(7)
    void deleteObject_triggersEventBridgeDelivery() {
        given()
        .when()
            .delete("/eb-s3-test-bucket/test-object.txt")
        .then()
            .statusCode(anyOf(equalTo(204), equalTo(200)));

        String messageBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");

        assert messageBody != null : "Expected a message in the queue after S3 delete";
        assert messageBody.contains("aws.s3") : "Expected source aws.s3 in: " + messageBody;
        assert messageBody.contains("Object Deleted") : "Expected detail-type 'Object Deleted' in: " + messageBody;
    }

    @Test
    @Order(8)
    void disableEventBridge_noMoreNotifications() {
        // Replace notification config with empty — no EventBridgeConfiguration
        given()
            .contentType("application/xml")
            .body("<NotificationConfiguration/>")
        .when()
            .put("/eb-s3-test-bucket?notification")
        .then()
            .statusCode(200);

        // Drain any leftover messages
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
        .when()
            .post("/");

        // Put a new object — should NOT deliver to EventBridge
        given()
            .contentType("text/plain")
            .body("quiet upload")
        .when()
            .put("/eb-s3-test-bucket/quiet.txt")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("aws.s3")));
    }

    @Test
    @Order(100)
    void cleanup() {
        given()
            .contentType(EB_CONTENT_TYPE)
            .header("X-Amz-Target", EB_TARGET + "RemoveTargets")
            .body("""
                {"Rule": "s3-event-rule", "Ids": ["1"]}
                """)
        .when()
            .post("/");

        given()
            .contentType(EB_CONTENT_TYPE)
            .header("X-Amz-Target", EB_TARGET + "DeleteRule")
            .body("""
                {"Name": "s3-event-rule"}
                """)
        .when()
            .post("/");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/");
    }
}
