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
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SnsHttpDeliveryIntegrationTest.class.getName());
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
        httpServer.createContext("/confirming-webhook", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            Map<String, List<String>> headers = exchange.getRequestHeaders();
            ReceivedRequest received = new ReceivedRequest(body, headers);
            try {
                // Confirm the subscription BEFORE recording the request, so a test that
                // awaits this request observes it only once the subscription is actually
                // confirmed. Recording first would let a subsequent publish race ahead of
                // the confirmation and see a still-pending subscription (flaky delivery).
                if ("SubscriptionConfirmation".equals(firstHeader(headers, "X-amz-sns-message-type"))) {
                    confirmSubscription(received);
                }
                receivedRequests.add(received);
                exchange.sendResponseHeaders(200, 0);
            } catch (Exception e) {
                // Record even on failure so awaitRequests unblocks and the assertion
                // reports the real state instead of timing out opaquely.
                receivedRequests.add(received);
                LOG.log(java.util.logging.Level.SEVERE,
                        "confirming-webhook: subscription confirmation failed", e);
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * Waits until the expected number of requests have been received, with a timeout.
     */
    private static void awaitRequests(int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (receivedRequests.size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Extracts the Token and TopicArn from the SubscriptionConfirmation message
     * and calls ConfirmSubscription via POST (matching how the SDK confirms).
     */
    private static void confirmSubscription(ReceivedRequest confirmationRequest) throws Exception {
        JsonNode node = objectMapper.readTree(confirmationRequest.body());
        assertEquals("SubscriptionConfirmation", node.get("Type").asText());
        String token = node.get("Token").asText();
        String topicArn = node.get("TopicArn").asText();
        assertNotNull(token);
        assertNotNull(topicArn);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ConfirmSubscription")
            .formParam("TopicArn", topicArn)
            .formParam("Token", token)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));
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

        // Subscribe HTTP endpoint — this sends SubscriptionConfirmation to the endpoint
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "http")
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        // The webhook should have received the SubscriptionConfirmation
        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        ReceivedRequest confirmReq = receivedRequests.get(0);
        assertEquals("SubscriptionConfirmation", confirmReq.headers().get("X-amz-sns-message-type").get(0));

        // Confirm the subscription
        confirmSubscription(confirmReq);
        receivedRequests.clear();

        // Publish message with message attributes
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Hello HTTP endpoint!")
            .formParam("Subject", "Test Subject")
            .formParam("MessageAttributes.entry.1.Name", "env")
            .formParam("MessageAttributes.entry.1.Value.DataType", "String")
            .formParam("MessageAttributes.entry.1.Value.StringValue", "production")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Verify webhook received the notification POST
        awaitRequests(1);
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
        assertEquals("EXAMPLE", envelope.get("SigningCertURL").asText());
        assertTrue(envelope.get("UnsubscribeURL").asText().contains("Action=Unsubscribe"));
        assertNotNull(envelope.get("MessageId").asText());
        assertNotNull(envelope.get("Timestamp").asText());

        // Verify message attributes in envelope
        JsonNode msgAttrs = envelope.get("MessageAttributes");
        assertNotNull(msgAttrs);
        assertTrue(msgAttrs.has("env"));
        assertEquals("String", msgAttrs.get("env").get("Type").asText());
        assertEquals("production", msgAttrs.get("env").get("Value").asText());
    }

    @Test
    void publish_toHttpSubscriber_confirmedDuringSuccessfulHandshake_deliversNotification() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/confirming-webhook";

        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-inline-confirm-test")
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
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        assertEquals("SubscriptionConfirmation", firstHeader(receivedRequests.get(0).headers(), "X-amz-sns-message-type"));
        receivedRequests.clear();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "delivered after inline confirm")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        ReceivedRequest req = receivedRequests.get(0);
        assertEquals("Notification", firstHeader(req.headers(), "X-amz-sns-message-type"));
        JsonNode envelope = objectMapper.readTree(req.body());
        assertEquals("Notification", envelope.get("Type").asText());
        assertEquals("delivered after inline confirm", envelope.get("Message").asText());
    }

    @Test
    void subscribeUrl_getConfirmsSubscriptionAndAllowsDelivery() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/webhook";

        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-subscribe-url-get-test")
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
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        JsonNode confirmation = objectMapper.readTree(receivedRequests.get(0).body());

        given()
            .queryParam("Action", "ConfirmSubscription")
            .queryParam("TopicArn", confirmation.get("TopicArn").asText())
            .queryParam("Token", confirmation.get("Token").asText())
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        receivedRequests.clear();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "delivered after SubscribeURL GET")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        assertEquals("Notification", firstHeader(receivedRequests.get(0).headers(), "X-amz-sns-message-type"));
    }

    @Test
    void unsubscribeUrl_getRoutesToSnsAndRemovesSubscription() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/webhook";
        // Pin the subscription to a non-default region. The SubscribeURL/UnsubscribeURL
        // links SNS emits are unsigned, so the delegation must recover this region from
        // the ARN in the link, not from the (absent) request auth.
        String snsAuth = "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-west-2/sns/aws4_request";

        String topicArn = given()
            .header("Authorization", snsAuth)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-unsubscribe-url-get-test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        assertTrue(topicArn.contains(":us-west-2:"), "topic should live in us-west-2");

        given()
            .header("Authorization", snsAuth)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "http")
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        JsonNode confirmation = objectMapper.readTree(receivedRequests.get(0).body());

        // Confirm via the SubscribeURL GET (unsigned) to obtain a real SubscriptionArn.
        // Region is recovered from the TopicArn, so the pending sub in us-west-2 is found.
        String subscriptionArn = given()
            .queryParam("Action", "ConfirmSubscription")
            .queryParam("TopicArn", confirmation.get("TopicArn").asText())
            .queryParam("Token", confirmation.get("Token").asText())
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"))
            .extract().xmlPath().getString("ConfirmSubscriptionResponse.ConfirmSubscriptionResult.SubscriptionArn");

        // Following the UnsubscribeURL is a plain GET on the service root. It must
        // reach SNS (UnsubscribeResponse), not fall through to S3 ListBuckets, and
        // its region must come from the SubscriptionArn (us-west-2), not the default.
        given()
            .queryParam("Action", "Unsubscribe")
            .queryParam("SubscriptionArn", subscriptionArn)
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("UnsubscribeResponse"))
            .body(not(containsString("ListAllMyBucketsResult")));

        // The unsubscribe took effect: the subscription is no longer listed.
        given()
            .header("Authorization", snsAuth)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListSubscriptionsByTopic")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(subscriptionArn)));
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
        given()
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

        // Confirm subscription
        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        confirmSubscription(receivedRequests.get(0));
        receivedRequests.clear();

        // Get the subscription ARN to set attributes
        String subscriptionArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListSubscriptionsByTopic")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Protocol == 'http' }.SubscriptionArn");

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
        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        ReceivedRequest req = receivedRequests.get(0);
        assertEquals("Raw message body", req.body());

        // SNS headers should still be present
        assertNotNull(req.headers().get("X-amz-sns-message-type"));
        assertEquals("Notification", req.headers().get("X-amz-sns-message-type").get(0));
        // Raw delivery marker header
        assertNotNull(req.headers().get("X-amz-sns-rawdelivery"));
        assertEquals("true", req.headers().get("X-amz-sns-rawdelivery").get(0));
    }

    @Test
    void publish_toUnreachableHttpEndpoint_doesNotFail() {
        // Subscribe to unreachable endpoint — SubscriptionConfirmation will fail but that's OK
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

        // Publish should still succeed (subscription is pending so delivery is skipped)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "This will not be delivered because subscription is unconfirmed")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    void subscribe_sendsSubscriptionConfirmation_withCorrectFields() throws Exception {
        receivedRequests.clear();
        String endpoint = "http://localhost:" + httpPort + "/webhook";

        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "http-confirm-fields-test")
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
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify SubscriptionConfirmation message fields
        awaitRequests(1);
        assertEquals(1, receivedRequests.size());
        ReceivedRequest req = receivedRequests.get(0);
        JsonNode body = objectMapper.readTree(req.body());
        assertEquals("SubscriptionConfirmation", body.get("Type").asText());
        assertEquals(topicArn, body.get("TopicArn").asText());
        assertNotNull(body.get("Token").asText());
        assertFalse(body.get("Token").asText().isBlank());
        assertTrue(body.get("SubscribeURL").asText().contains("Action=ConfirmSubscription"));
        assertTrue(body.get("SubscribeURL").asText().contains("Token="));
        assertEquals("1", body.get("SignatureVersion").asText());
        assertNotNull(body.get("MessageId").asText());
        assertNotNull(body.get("Timestamp").asText());

        // Subscription ARN header must be "PendingConfirmation" for confirmation requests
        assertNotNull(req.headers().get("X-amz-sns-subscription-arn"));
        assertEquals("PendingConfirmation", req.headers().get("X-amz-sns-subscription-arn").get(0));
    }
}
