package io.github.hectorvent.floci.services.eventbridge;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * Integration tests for EventBridge ARN-based EventBusName support.
 * AWS EventBridge APIs accept EventBusName as either a name OR an ARN.
 * This test suite validates that Floci correctly handles both formats.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeArnIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";

    private static String customBusArn;
    private static String customBusName;
    private static String sinkQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setup_createCustomEventBus() {
        customBusName = "arn-test-bus";
        customBusArn = given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + customBusName + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("EventBusArn", notNullValue())
                .extract().jsonPath().getString("EventBusArn");
    }

    @Test
    @Order(2)
    void setup_createSinkQueue() {
        sinkQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"arn-test-sink-queue\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
    }

    @Test
    @Order(3)
    void putRule_withArnAsEventBusName() {
        // Create a rule using ARN as EventBusName instead of plain name
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                {
                    "Name": "arn-test-rule",
                    "EventBusName": "%s",
                    "EventPattern": "{\\"source\\":[\\"test.event\\"]}",
                    "State": "ENABLED"
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RuleArn", notNullValue())
                .body("RuleArn", containsString("arn-test-bus"));
    }

    @Test
    @Order(4)
    void describeRule_withArnAsEventBusName() {
        // Describe the rule using ARN as EventBusName
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DescribeRule")
                .body("""
                {
                    "Name": "arn-test-rule",
                    "EventBusName": "%s"
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Name", equalTo("arn-test-rule"))
                .body("EventBusName", equalTo(customBusName))
                .body("State", equalTo("ENABLED"));
    }

    @Test
    @Order(5)
    void putTargets_withArnAsEventBusName() {
        String queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + sinkQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when()
                .post("/0000000000/arn-test-sink-queue")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                {
                    "Rule": "arn-test-rule",
                    "EventBusName": "%s",
                    "Targets": [{
                        "Id": "1",
                        "Arn": "%s"
                    }]
                }
                """.formatted(customBusArn, queueArn))
                .when().post("/")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(6)
    void listTargetsByRule_withArnAsEventBusName() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("""
                {
                    "Rule": "arn-test-rule",
                    "EventBusName": "%s"
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Targets", hasSize(1))
                .body("Targets[0].Id", equalTo("1"));
    }

    @Test
    @Order(7)
    void putEvents_withArnAsEventBusName() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                {
                    "Entries": [{
                        "Source": "test.event",
                        "DetailType": "TestEvent",
                        "Detail": "{\\"testKey\\":\\"testValue\\"}",
                        "EventBusName": "%s"
                    }]
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(8)
    void removeTargets_withArnAsEventBusName() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.RemoveTargets")
                .body("""
                {
                    "Rule": "arn-test-rule",
                    "EventBusName": "%s",
                    "Ids": ["1"]
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(9)
    void deleteRule_withArnAsEventBusName() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteRule")
                .body("""
                {
                    "Name": "arn-test-rule",
                    "EventBusName": "%s"
                }
                """.formatted(customBusArn))
                .when().post("/")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(10)
    void deleteEventBus_withArn() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + customBusArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(11)
    void describeEventBus_withArn() {
        // Create another bus for this test
        String busName = "describe-arn-test-bus";
        String busArn = given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("EventBusArn");

        // Describe using ARN
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
                .body("{\"Name\":\"" + busArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Name", equalTo(busName))
                .body("Arn", equalTo(busArn));

        // Cleanup
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/");
    }

    @Test
    @Order(12)
    void enableRule_withArnAsEventBusName() {
        // Create a custom bus and rule for this test
        String busName = "enable-rule-arn-test-bus";
        String busArn = given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("EventBusArn");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                {
                    "Name": "enable-rule-test",
                    "EventBusName": "%s",
                    "EventPattern": "{\\"source\\":[\\"test\\"]}",
                    "State": "DISABLED"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200);

        // Enable rule using ARN as EventBusName
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.EnableRule")
                .body("""
                {
                    "Name": "enable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200);

        // Verify rule is enabled
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DescribeRule")
                .body("""
                {
                    "Name": "enable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("State", equalTo("ENABLED"));

        // Cleanup
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteRule")
                .body("""
                {
                    "Name": "enable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/");
    }

    @Test
    @Order(13)
    void disableRule_withArnAsEventBusName() {
        // Create a custom bus and rule for this test
        String busName = "disable-rule-arn-test-bus";
        String busArn = given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("EventBusArn");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                {
                    "Name": "disable-rule-test",
                    "EventBusName": "%s",
                    "EventPattern": "{\\"source\\":[\\"test\\"]}",
                    "State": "ENABLED"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200);

        // Disable rule using ARN as EventBusName
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DisableRule")
                .body("""
                {
                    "Name": "disable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200);

        // Verify rule is disabled
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DescribeRule")
                .body("""
                {
                    "Name": "disable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("State", equalTo("DISABLED"));

        // Cleanup
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteRule")
                .body("""
                {
                    "Name": "disable-rule-test",
                    "EventBusName": "%s"
                }
                """.formatted(busArn))
                .when().post("/");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + busName + "\"}")
                .when().post("/");
    }
}
