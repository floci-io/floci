package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for SES V2 event publishing: SES SendEmail with a
 * ConfigurationSetName whose event destination points at an SNS topic results in
 * AWS-format event JSON being published to that topic and delivered to a
 * subscribed SQS queue.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesEventPublishingV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sqs/aws4_request";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SNS_CONTENT_TYPE = "application/x-amz-json-1.0";

    private static final String CS = "ses-events-cs";
    private static final String SENDER = "evt-from@floci.test";

    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupSqsQueueAndSnsTopicAndSubscription() {
        queueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"ses-events-queue\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(queueUrl);

        queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        assertNotNull(queueArn);

        topicArn = given()
                .contentType(SNS_CONTENT_TYPE)
                .header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .body("{\"Name\":\"ses-events-topic\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("TopicArn");
        assertNotNull(topicArn);

        given()
                .contentType(SNS_CONTENT_TYPE)
                .header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\"" + queueArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void setupSesIdentityConfigSetAndEventDestination() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when()
                .post("/v2/email/identities")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-sns",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND", "DELIVERY", "BOUNCE", "COMPLAINT", "REJECT"],
                        "SnsDestination": {"TopicArn": "%s"}
                      }
                    }
                    """.formatted(topicArn))
        .when()
                .post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(3)
    void sendToSuccessSimulator_publishesSendAndDelivery() throws Exception {
        drainQueue();
        sendEmail("success@simulator.amazonses.com");
        List<JsonNode> events = receiveSesEvents(2);
        assertEquals(2, events.size(), "expected Send and Delivery events");
        assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())));
        assertTrue(events.stream().anyMatch(e -> "Delivery".equals(e.path("eventType").asText())));
        JsonNode delivery = events.stream()
                .filter(e -> "Delivery".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals(SENDER, delivery.path("mail").path("source").asText());
        assertEquals("success@simulator.amazonses.com",
                delivery.path("delivery").path("recipients").get(0).asText());
        assertEquals(CS, delivery.path("mail").path("tags").path("ses:configuration-set").get(0).asText());
        assertEquals("evt", delivery.path("mail").path("commonHeaders").path("subject").asText());
        assertEquals("success@simulator.amazonses.com",
                delivery.path("mail").path("commonHeaders").path("to").get(0).asText());
        assertTrue(delivery.path("mail").path("commonHeaders").path("date").asText().length() > 0);
    }

    @Test
    @Order(4)
    void sendToBounceSimulator_publishesSendAndBounce() throws Exception {
        drainQueue();
        sendEmail("bounce@simulator.amazonses.com");
        List<JsonNode> events = receiveSesEvents(2);
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())));
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals("Permanent", bounce.path("bounce").path("bounceType").asText());
        assertEquals("bounce@simulator.amazonses.com",
                bounce.path("bounce").path("bouncedRecipients").get(0).path("emailAddress").asText());
        assertEquals("evt", bounce.path("mail").path("commonHeaders").path("subject").asText());
    }

    @Test
    @Order(5)
    void sendToRegularAddress_publishesSendOnly() throws Exception {
        drainQueue();
        sendEmail("recipient@example.com");
        List<JsonNode> events = receiveSesEvents(1);
        assertEquals(1, events.size());
        assertEquals("Send", events.get(0).path("eventType").asText());
    }

    @Test
    @Order(6)
    void v1SendRawEmail_recipientOnlyInMimeHeader_publishesBounceFromMimeTo() throws Exception {
        drainQueue();
        String raw = "From: " + SENDER + "\r\n"
                + "To: bounce@simulator.amazonses.com\r\n"
                + "Subject: mime-only\r\n\r\nbody";
        String rawB64 = java.util.Base64.getEncoder().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .body("Action=SendRawEmail"
                        + "&Source=" + java.net.URLEncoder.encode(SENDER, java.nio.charset.StandardCharsets.UTF_8)
                        + "&RawMessage.Data=" + java.net.URLEncoder.encode(rawB64, java.nio.charset.StandardCharsets.UTF_8)
                        + "&ConfigurationSetName=" + CS
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(2);
        assertEquals(2, events.size(), "expected Send and Bounce events from MIME-only recipient");
        assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())));
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals("bounce@simulator.amazonses.com",
                bounce.path("bounce").path("bouncedRecipients").get(0).path("emailAddress").asText());
        assertEquals("mime-only", bounce.path("mail").path("commonHeaders").path("subject").asText());
        assertEquals("bounce@simulator.amazonses.com",
                bounce.path("mail").path("commonHeaders").path("to").get(0).asText());
    }

    @Test
    @Order(7)
    void disabledEventDestination_skipsPublish() throws Exception {
        String csDisabled = "v2-cs-ed-disabled";
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + csDisabled + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-sns-disabled",
                      "EventDestination": {
                        "Enabled": false,
                        "MatchingEventTypes": ["SEND", "DELIVERY", "BOUNCE"],
                        "SnsDestination": {"TopicArn": "%s"}
                      }
                    }
                    """.formatted(topicArn))
        .when()
                .post("/v2/email/configuration-sets/" + csDisabled + "/event-destinations")
        .then()
                .statusCode(200);

        drainQueue();

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["success@simulator.amazonses.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "should-not-publish"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, csDisabled))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(0);
        assertEquals(0, events.size(), "disabled event destination should skip publishing");
    }

    @Test
    @Order(8)
    void mixedRecipients_filterBouncedRecipientsToSimulatorOnly() throws Exception {
        drainQueue();
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["normal@example.com", "bounce@simulator.amazonses.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "mixed"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, CS))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(2);
        assertEquals(2, events.size(), "expected Send and Bounce events");
        assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())));
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        JsonNode bouncedRecipients = bounce.path("bounce").path("bouncedRecipients");
        assertEquals(1, bouncedRecipients.size(),
                "bouncedRecipients should contain only the simulator address, not normal recipients");
        assertEquals("bounce@simulator.amazonses.com",
                bouncedRecipients.get(0).path("emailAddress").asText());
        // mail.destination keeps the full envelope recipient list
        assertEquals(2, bounce.path("mail").path("destination").size());
    }

    @Test
    @Order(9)
    void sendEmail_emailTagsPropagateIntoMailTags() throws Exception {
        drainQueue();
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["success@simulator.amazonses.com"]},
                      "ConfigurationSetName": "%s",
                      "EmailTags": [
                        {"Name": "campaign", "Value": "launch"},
                        {"Name": "env", "Value": "prod"}
                      ],
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "tagged"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, CS))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(2);
        assertTrue(events.size() >= 1, "expected at least Send event");
        JsonNode send = events.stream()
                .filter(e -> "Send".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        JsonNode tags = send.path("mail").path("tags");
        assertEquals(CS, tags.path("ses:configuration-set").get(0).asText());
        assertEquals("launch", tags.path("campaign").get(0).asText());
        assertEquals("prod", tags.path("env").get(0).asText());
    }

    @Test
    @Order(10)
    void v1SendEmail_messageTagsPropagateIntoMailTags() throws Exception {
        drainQueue();
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .body("Action=SendEmail"
                        + "&Source=" + java.net.URLEncoder.encode(SENDER, java.nio.charset.StandardCharsets.UTF_8)
                        + "&Destination.ToAddresses.member.1="
                        + java.net.URLEncoder.encode("success@simulator.amazonses.com",
                                java.nio.charset.StandardCharsets.UTF_8)
                        + "&Message.Subject.Data=v1tagged"
                        + "&Message.Body.Text.Data=hi"
                        + "&Tags.member.1.Name=campaign&Tags.member.1.Value=v1launch"
                        + "&Tags.member.2.Name=env&Tags.member.2.Value=staging"
                        + "&ConfigurationSetName=" + CS
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(2);
        JsonNode send = events.stream()
                .filter(e -> "Send".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        JsonNode tags = send.path("mail").path("tags");
        assertEquals("v1launch", tags.path("campaign").get(0).asText());
        assertEquals("staging", tags.path("env").get(0).asText());
    }

    @Test
    @Order(11)
    void sendEmail_simpleHeadersAppearInEventMailHeaders() throws Exception {
        drainQueue();
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["success@simulator.amazonses.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "hdr"},
                          "Body": {"Text": {"Data": "hi"}},
                          "Headers": [
                            {"Name": "X-Mailer", "Value": "floci"},
                            {"Name": "List-Unsubscribe", "Value": "<mailto:u@example.com>"}
                          ]
                        }
                      }
                    }
                    """.formatted(SENDER, CS))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<JsonNode> events = receiveSesEvents(2);
        JsonNode send = events.stream()
                .filter(e -> "Send".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        JsonNode headers = send.path("mail").path("headers");
        boolean hasXMailer = false;
        boolean hasUnsubscribe = false;
        for (JsonNode h : headers) {
            if ("X-Mailer".equals(h.path("name").asText())
                    && "floci".equals(h.path("value").asText())) {
                hasXMailer = true;
            }
            if ("List-Unsubscribe".equals(h.path("name").asText())) {
                hasUnsubscribe = true;
            }
        }
        assertTrue(hasXMailer, "expected X-Mailer header in event mail.headers");
        assertTrue(hasUnsubscribe, "expected List-Unsubscribe header in event mail.headers");
    }

    private void sendEmail(String to) {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["%s"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "evt"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, to, CS))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);
    }

    private void drainQueue() {
        for (int i = 0; i < 5; i++) {
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl
                            + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":0}")
            .when()
                    .post("/")
            .then()
                    .statusCode(200)
                    .extract().response();
            List<String> handles = r.jsonPath().getList("Messages.ReceiptHandle");
            if (handles == null || handles.isEmpty()) {
                return;
            }
            deleteMessages(handles);
        }
    }

    private List<JsonNode> receiveSesEvents(int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        // When we're confirming "no event arrives", a couple of long-poll attempts is
        // enough — SES → SNS → SQS propagates in seconds locally. Keep 10 attempts only
        // when we're actually waiting for events to appear.
        int maxAttempts = expectedAtLeast > 0 ? 10 : 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (expectedAtLeast > 0 && events.size() >= expectedAtLeast) {
                break;
            }
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl
                            + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when()
                    .post("/")
            .then()
                    .statusCode(200)
                    .extract().response();
            List<String> bodies = r.jsonPath().getList("Messages.Body");
            List<String> handles = r.jsonPath().getList("Messages.ReceiptHandle");
            if (bodies == null || bodies.isEmpty()) {
                continue;
            }
            for (String body : bodies) {
                JsonNode snsWrapper = MAPPER.readTree(body);
                JsonNode sesEvent = MAPPER.readTree(snsWrapper.path("Message").asText());
                events.add(sesEvent);
            }
            deleteMessages(handles);
        }
        return events;
    }

    private void deleteMessages(List<String> receiptHandles) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < receiptHandles.size(); i++) {
            if (i > 0) {
                entries.append(",");
            }
            entries.append("{\"Id\":\"m").append(i).append("\",\"ReceiptHandle\":\"")
                    .append(receiptHandles.get(i)).append("\"}");
        }
        given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.DeleteMessageBatch")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"Entries\":[" + entries + "]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}
