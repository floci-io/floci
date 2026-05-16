package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SNS HTTP/HTTPS endpoint delivery.
 * Uses an embedded HTTP server to receive webhook POSTs.
 */
@QuarkusTest
class SnsHttpDeliveryIntegrationTest {

    private static HttpServer httpServer;
    private static int httpPort;
    private static final List<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    record ReceivedRequest(String body, Map<String, List<String>> headers) {}

    @BeforeAll
    static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpPort = httpServer.getAddress().getPort();
        httpServer.createContext("/webhook", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            Map<String, List<String>> headers = exchange.getRequestHeaders();
            receivedRequests.add(new ReceivedRequest(body, headers));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void publish_toHttpSubscriber_deliversNotificationEnvelope() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/webhook";

        // Create topic
        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-delivery-test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        // Subscribe HTTP endpoint
        String subscriptionArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "http")
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"))
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        // Subscription should be auto-confirmed (not pending)
        assertFalse(subscriptionArn.contains("pending"));

        // Publish message
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Hello HTTP endpoint!")
            .formParam("Subject", "Test Subject")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Verify webhook received the POST
        assertEquals(1, receivedRequests.size());
        ReceivedRequest req = receivedRequests.get(0);

        // Verify SNS headers
        assertNotNull(req.headers().get("X-amz-sns-message-type"));
        assertEquals("Notification", req.headers().get("X-amz-sns-message-type").get(0));
        assertNotNull(req.headers().get("X-amz-sns-topic-arn"));
        assertEquals(topicArn, req.headers().get("X-amz-sns-topic-arn").get(0));
        assertNotNull(req.headers().get("X-amz-sns-message-id"));
        assertNotNull(req.headers().get("X-amz-sns-subscription-arn"));

        // Verify body is a proper SNS notification envelope
        JsonNode envelope = objectMapper.readTree(req.body());
        assertEquals("Notification", envelope.get("Type").asText());
        assertEquals(topicArn, envelope.get("TopicArn").asText());
        assertEquals("Hello HTTP endpoint!", envelope.get("Message").asText());
        assertEquals("Test Subject", envelope.get("Subject").asText());
        assertEquals("1", envelope.get("SignatureVersion").asText());
        assertEquals("EXAMPLE", envelope.get("Signature").asText());
        assertEquals("EXAMPLE", envelope.get("SigningCertUrl").asText());
        assertTrue(envelope.get("UnsubscribeURL").asText().contains("Action=Unsubscribe"));
        assertNotNull(envelope.get("MessageId").asText());
        assertNotNull(envelope.get("Timestamp").asText());
    }

    @Test
    void publish_toHttpSubscriber_rawMessageDelivery() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/webhook";

        // Create topic
        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-raw-delivery-test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        // Subscribe HTTP endpoint
        String subscriptionArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "http")
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        // Set RawMessageDelivery attribute
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetSubscriptionAttributes")
            .formParam("SubscriptionArn", subscriptionArn)
            .formParam("AttributeName", "RawMessageDelivery")
            .formParam("AttributeValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Publish message
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Raw message body")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Verify webhook received raw message
        assertEquals(1, receivedRequests.size());
        ReceivedRequest req = receivedRequests.get(0);
        assertEquals("Raw message body", req.body());

        // SNS headers should still be present
        assertNotNull(req.headers().get("X-amz-sns-message-type"));
        assertEquals("Notification", req.headers().get("X-amz-sns-message-type").get(0));
    }

    @Test
    void publish_toUnreachableHttpEndpoint_doesNotFail() {
        // Subscribe to unreachable endpoint
        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-unreachable-test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "http")
            .formParam("Endpoint", "http://localhost:1/unreachable")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Publish should still succeed (delivery failure is caught internally)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "This will fail delivery but not the publish")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }
}
