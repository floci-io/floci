package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;

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

    private static final String XA_SOURCE_BUS = "xa-source-bus";
    private static final String XA_TARGET_BUS = "xa-target-bus";
    private static final String XA_QUEUE = "xa-sink-queue";
    private static final String XA_FORWARD_RULE = "xa-forward-rule";
    private static final String XA_SINK_RULE = "xa-sink-rule";

    private static final String AUTH_1_EVENTS = "AWS4-HMAC-SHA256 "
            + "Credential=000000000001/20260805/us-east-1/events/aws4_request, "
            + "SignedHeaders=host, Signature=abc";
    private static final String AUTH_2_EVENTS = "AWS4-HMAC-SHA256 "
            + "Credential=000000000002/20260805/us-east-1/events/aws4_request, "
            + "SignedHeaders=host, Signature=abc";
    private static final String AUTH_2_SQS = "AWS4-HMAC-SHA256 "
            + "Credential=000000000002/20260805/us-east-1/sqs/aws4_request, "
            + "SignedHeaders=host, Signature=abc";

    private static String xaQueueUrl;
    private static String xaQueuePath;
    private static String xaTargetBusArn;

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

    @Test
    @Order(3)
    void setupCrossAccountTopology() {
        // Source bus in account 1.
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .header("Authorization", AUTH_1_EVENTS)
                .body("{\"Name\":\"" + XA_SOURCE_BUS + "\"}")
                .when().post("/").then().statusCode(200);

        // Target bus in account 2 — the ARN it returns carries account 2.
        xaTargetBusArn = given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .header("Authorization", AUTH_2_EVENTS)
                .body("{\"Name\":\"" + XA_TARGET_BUS + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("EventBusArn");

        // SQS sink in account 2. SQS is the only target type that resolves
        // cross-account today, so it is the only sink that can prove delivery here.
        xaQueueUrl = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .header("Authorization", AUTH_2_SQS)
                .body("{\"QueueName\":\"" + XA_QUEUE + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        xaQueuePath = URI.create(xaQueueUrl).getPath();

        String xaQueueArn = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .header("Authorization", AUTH_2_SQS)
                .body("{\"QueueUrl\":\"" + xaQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post(xaQueuePath).then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        // Rule on account 2's target bus → its own SQS queue.
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .header("Authorization", AUTH_2_EVENTS)
                .body("{\"Name\":\"" + XA_SINK_RULE + "\",\"EventBusName\":\"" + XA_TARGET_BUS + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"myapp.xa\\\"]}\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutTargets")
                .header("Authorization", AUTH_2_EVENTS)
                .body("{\"Rule\":\"" + XA_SINK_RULE + "\",\"EventBusName\":\"" + XA_TARGET_BUS + "\","
                        + "\"Targets\":[{\"Id\":\"sink\",\"Arn\":\"" + xaQueueArn + "\"}]}")
                .when().post("/").then().statusCode(200);

        // Rule on account 1's source bus → account 2's bus, by ARN.
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .header("Authorization", AUTH_1_EVENTS)
                .body("{\"Name\":\"" + XA_FORWARD_RULE + "\",\"EventBusName\":\"" + XA_SOURCE_BUS + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"myapp.xa\\\"]}\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutTargets")
                .header("Authorization", AUTH_1_EVENTS)
                .body("{\"Rule\":\"" + XA_FORWARD_RULE + "\",\"EventBusName\":\"" + XA_SOURCE_BUS + "\","
                        + "\"Targets\":[{\"Id\":\"forward\",\"Arn\":\"" + xaTargetBusArn + "\"}]}")
                .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(4)
    void publishInAccountOne_forwardsToAccountTwoBus_andReachesItsQueue() {
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutEvents")
                .header("Authorization", AUTH_1_EVENTS)
                .body("""
                        {"Entries":[{
                        "EventBusName":"%s",
                        "Source":"myapp.xa",
                        "DetailType":"Order.Created",
                        "Detail":"{\\"orderId\\":\\"xa-1\\"}"
                        }]}
                        """.formatted(XA_SOURCE_BUS))
                .when().post("/").then().statusCode(200);

        given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .header("Authorization", AUTH_2_SQS)
                .body("{\"QueueUrl\":\"" + xaQueueUrl + "\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":2}")
                .when().post(xaQueuePath)
                .then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", containsString("\"detail\":{\"orderId\":\"xa-1\"}"))
                .body("Messages[0].Body", containsString("\"event-bus-name\":\"" + XA_TARGET_BUS + "\""))
                // The envelope keeps the originating account, not the target bus's.
                .body("Messages[0].Body", containsString("\"account\":\"000000000001\""));
    }
}
