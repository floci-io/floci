package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * End-to-end EventBridge bus→bus target delivery: a rule on the source bus targets a
 * second event bus by ARN, whose rule then delivers to SQS. Mirrors a two-bus routing
 * topology where a shared bus forwards domain-scoped events to a domain-specific bus.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeBusToBusIntegrationTest {

    private static final String SQS_CT = "application/x-amz-json-1.0";
    private static final String EB_CT = "application/x-amz-json-1.1";

    private static final String SOURCE_BUS = "b2b-source-bus";
    private static final String TARGET_BUS = "b2b-target-bus";
    private static final String QUEUE = "b2b-sink-queue";
    private static final String FORWARD_RULE = "b2b-forward-rule";
    private static final String SINK_RULE = "b2b-sink-rule";

    private static String queueUrl;
    private static String queueArn;
    private static String targetBusArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupBusesQueueAndRules() {
        // Two custom buses
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + SOURCE_BUS + "\"}")
                .when().post("/").then().statusCode(200);

        targetBusArn = given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + TARGET_BUS + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("EventBusArn");

        // SQS sink
        queueUrl = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + QUEUE + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        queueArn = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/0000000000/" + QUEUE).then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        // Rule on source bus → target bus (the branch under test)
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + FORWARD_RULE + "\",\"EventBusName\":\"" + SOURCE_BUS + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"myapp.orders\\\"]}\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"" + FORWARD_RULE + "\",\"EventBusName\":\"" + SOURCE_BUS + "\","
                        + "\"Targets\":[{\"Id\":\"forward\",\"Arn\":\"" + targetBusArn + "\"}]}")
                .when().post("/").then().statusCode(200);

        // Rule on target bus → SQS
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + SINK_RULE + "\",\"EventBusName\":\"" + TARGET_BUS + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"myapp.orders\\\"]}\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"" + SINK_RULE + "\",\"EventBusName\":\"" + TARGET_BUS + "\","
                        + "\"Targets\":[{\"Id\":\"sink\",\"Arn\":\"" + queueArn + "\"}]}")
                .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void publishOnSourceBus_forwardsThroughTargetBus_toSqs() {
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {"Entries":[{
                        "EventBusName":"%s",
                        "Source":"myapp.orders",
                        "DetailType":"Order.Created",
                        "Resources":["arn:aws:s3:::b2b-bucket"],
                        "Detail":"{\\"orderId\\":\\"o-1\\"}"
                        }]}
                        """.formatted(SOURCE_BUS))
                .when().post("/").then().statusCode(200);

        given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":2}")
                .when().post("/0000000000/" + QUEUE)
                .then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", containsString("\"source\":\"myapp.orders\""))
                .body("Messages[0].Body", containsString("\"detail-type\":\"Order.Created\""))
                // Detail must be unwrapped, not the whole envelope re-wrapped.
                .body("Messages[0].Body", containsString("\"detail\":{\"orderId\":\"o-1\"}"))
                .body("Messages[0].Body", containsString("\"resources\":[\"arn:aws:s3:::b2b-bucket\"]"))
                .body("Messages[0].Body", containsString("\"event-bus-name\":\"" + TARGET_BUS + "\""));
    }
}
