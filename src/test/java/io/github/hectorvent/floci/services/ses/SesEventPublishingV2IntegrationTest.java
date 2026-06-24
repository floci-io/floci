package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.MetricIdentity;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for SES V2 event publishing. SES SendEmail with a
 * ConfigurationSetName whose event destination points at one of the supported
 * destination types — {@code SnsDestination}, {@code KinesisFirehoseDestination},
 * {@code EventBridgeDestination}, or {@code CloudWatchDestination} — results in
 * AWS-format event JSON / metric being delivered to that destination's natural
 * sink and asserted there. Each destination has its own configuration set and
 * runs after the previous block via {@link Order}.
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
    private static final String EVENTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/events/aws4_request";
    private static final String FIREHOSE_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/firehose/aws4_request";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SNS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENTS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String FIREHOSE_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String CS = "ses-events-cs";
    private static final String CS_FIREHOSE = "ses-events-firehose-cs";
    private static final String CS_EB = "ses-events-eb-cs";
    private static final String CS_CW = "ses-events-cw-cs";
    private static final String SENDER = "evt-from@floci.test";
    private static final String FIREHOSE_STREAM = "ses-events-stream";
    private static final String FIREHOSE_BUCKET = "ses-events-firehose-bucket";
    private static final String EB_QUEUE = "ses-events-eb-queue";
    private static final String EB_RULE = "ses-events-eb-rule";
    private static final String EB_DEFAULT_BUS_ARN =
            "arn:aws:events:us-east-1:000000000000:event-bus/default";
    private static final String CW_REGION = "us-east-1";
    private static final String CW_NAMESPACE = "AWS/SES";

    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;
    private static String firehoseStreamArn;
    private static String ebQueueUrl;
    private static String ebQueueArn;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    S3Service s3Service;

    @Inject
    CloudWatchMetricsService metricsService;

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

    @Test
    @Order(12)
    void sendToSuppressedAddress_withBounceReason_publishesSyntheticBounceEvent() throws Exception {
        String suppressed = "bounce-suppressed-" + System.nanoTime() + "@example.com";
        // Pre-register the address in the account-level suppression list with reason BOUNCE.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"EmailAddress\":\"" + suppressed + "\",\"Reason\":\"BOUNCE\"}")
        .when()
                .put("/v2/email/suppression/addresses")
        .then()
                .statusCode(200);

        try {
            drainQueue();
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
                        """.formatted(SENDER, suppressed, CS))
            .when()
                    .post("/v2/email/outbound-emails")
            .then()
                    .statusCode(200);

            List<JsonNode> events = receiveSesEvents(2);
            assertEquals(2, events.size(), "expected Send and synthetic Bounce events");
            assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())));
            JsonNode bounce = events.stream()
                    .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                    .findFirst().orElseThrow();
            assertEquals(suppressed,
                    bounce.path("bounce").path("bouncedRecipients").get(0).path("emailAddress").asText());
        } finally {
            // Always run cleanup so the suppression list doesn't leak into subsequent tests.
            given()
                    .header("Authorization", SES_AUTH)
            .when()
                    .delete("/v2/email/suppression/addresses/" + suppressed);
        }
    }

    // ============================== Firehose ==============================

    @Test
    @Order(14)
    void firehose_setupStreamAndConfigSet() {
        firehoseStreamArn = given()
                .contentType(FIREHOSE_CONTENT_TYPE)
                .header("Authorization", FIREHOSE_AUTH)
                .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
                .body("""
                    {"DeliveryStreamName": "%s",
                     "S3DestinationConfiguration": {"BucketARN": "arn:aws:s3:::%s"}}
                    """.formatted(FIREHOSE_STREAM, FIREHOSE_BUCKET))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("DeliveryStreamARN");
        assertNotNull(firehoseStreamArn);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS_FIREHOSE + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-firehose",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "KinesisFirehoseDestination": {
                          "IamRoleArn": "arn:aws:iam::000000000000:role/ses-firehose-role",
                          "DeliveryStreamArn": "%s"
                        }
                      }
                    }
                    """.formatted(firehoseStreamArn))
        .when()
                .post("/v2/email/configuration-sets/" + CS_FIREHOSE + "/event-destinations")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(13)
    void sendToSuppressedAddress_withComplaintReason_publishesSyntheticComplaintEvent() throws Exception {
        String suppressed = "complaint-suppressed-" + System.nanoTime() + "@example.com";
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"EmailAddress\":\"" + suppressed + "\",\"Reason\":\"COMPLAINT\"}")
        .when()
                .put("/v2/email/suppression/addresses")
        .then()
                .statusCode(200);

        try {
            drainQueue();
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
                        """.formatted(SENDER, suppressed, CS))
            .when()
                    .post("/v2/email/outbound-emails")
            .then()
                    .statusCode(200);

            List<JsonNode> events = receiveSesEvents(2);
            assertEquals(2, events.size(), "expected Send and synthetic Complaint events");
            JsonNode complaint = events.stream()
                    .filter(e -> "Complaint".equals(e.path("eventType").asText()))
                    .findFirst().orElseThrow();
            assertEquals(suppressed,
                    complaint.path("complaint").path("complainedRecipients").get(0).path("emailAddress").asText());
        } finally {
            given()
                    .header("Authorization", SES_AUTH)
            .when()
                    .delete("/v2/email/suppression/addresses/" + suppressed);
        }
    }

    @Test
    @Order(15)
    void firehose_fiveSends_triggerAutoFlushAndWriteNdjsonToS3() throws Exception {
        for (int i = 0; i < 5; i++) {
            sendEmailToConfigSet(CS_FIREHOSE, "recipient" + i + "@example.com", "fh-evt-" + i);
        }

        List<S3Object> objects = s3Service.listObjects(FIREHOSE_BUCKET, FIREHOSE_STREAM + "/", null, 100);
        assertEquals(1, objects.size(),
                "expected exactly one flushed S3 object after 5 putRecord calls (DEFAULT_FLUSH_COUNT)");

        S3Object obj = s3Service.getObject(FIREHOSE_BUCKET, objects.get(0).getKey());
        String body = new String(obj.getData(), StandardCharsets.UTF_8);
        String[] lines = body.split("\\R");
        assertEquals(5, lines.length, "flushed object should contain 5 NDJSON records");

        for (int i = 0; i < 5; i++) {
            JsonNode event = MAPPER.readTree(lines[i]);
            assertEquals("Send", event.path("eventType").asText());
            assertEquals(SENDER, event.path("mail").path("source").asText());
            assertEquals(CS_FIREHOSE,
                    event.path("mail").path("tags").path("ses:configuration-set").get(0).asText());
        }
    }

    @Test
    @Order(16)
    void firehose_disabledEventDestination_doesNotPublish() throws Exception {
        String csDisabled = "ses-events-firehose-cs-disabled";
        String streamDisabled = "ses-events-stream-disabled";

        // Reuse FIREHOSE_BUCKET so listObjects never hits a missing-bucket condition;
        // the disabled stream just has its own prefix which should stay empty.
        String disabledStreamArn = given()
                .contentType(FIREHOSE_CONTENT_TYPE)
                .header("Authorization", FIREHOSE_AUTH)
                .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
                .body("""
                    {"DeliveryStreamName": "%s",
                     "S3DestinationConfiguration": {"BucketARN": "arn:aws:s3:::%s"}}
                    """.formatted(streamDisabled, FIREHOSE_BUCKET))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("DeliveryStreamARN");

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
                      "EventDestinationName": "ed-firehose-disabled",
                      "EventDestination": {
                        "Enabled": false,
                        "MatchingEventTypes": ["SEND"],
                        "KinesisFirehoseDestination": {
                          "IamRoleArn": "arn:aws:iam::000000000000:role/ses-firehose-role",
                          "DeliveryStreamArn": "%s"
                        }
                      }
                    }
                    """.formatted(disabledStreamArn))
        .when()
                .post("/v2/email/configuration-sets/" + csDisabled + "/event-destinations")
        .then()
                .statusCode(200);

        for (int i = 0; i < 5; i++) {
            sendEmailToConfigSet(csDisabled, "recipient" + i + "@example.com", "fh-disabled-" + i);
        }

        List<S3Object> objects = s3Service.listObjects(FIREHOSE_BUCKET, streamDisabled + "/", null, 100);
        assertTrue(objects.isEmpty(),
                "disabled event destination should not produce any flushed S3 object");
    }

    @Test
    @Order(17)
    void firehose_unknownStreamArn_sendStillSucceedsAndDoesNotCrash() throws Exception {
        String csUnknown = "ses-events-firehose-cs-unknown";
        String bogusStreamArn =
                "arn:aws:firehose:us-east-1:000000000000:deliverystream/does-not-exist";

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + csUnknown + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-firehose-unknown",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "KinesisFirehoseDestination": {
                          "IamRoleArn": "arn:aws:iam::000000000000:role/ses-firehose-role",
                          "DeliveryStreamArn": "%s"
                        }
                      }
                    }
                    """.formatted(bogusStreamArn))
        .when()
                .post("/v2/email/configuration-sets/" + csUnknown + "/event-destinations")
        .then()
                .statusCode(200);

        // SendEmail must still succeed even though the Firehose dispatch hits a missing stream.
        sendEmailToConfigSet(csUnknown, "recipient@example.com", "fh-unknown");

        // Sanity: the main stream's S3 contents are unchanged by this test.
        List<S3Object> objects = s3Service.listObjects(FIREHOSE_BUCKET, FIREHOSE_STREAM + "/", null, 100);
        assertFalse(objects.isEmpty(),
                "main stream's S3 contents should still be present (sanity)");
    }

    // ============================ EventBridge ============================

    @Test
    @Order(18)
    void eventBridge_setupSqsQueueAndRuleAndConfigSet() {
        ebQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + EB_QUEUE + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(ebQueueUrl);

        ebQueueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + ebQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        assertNotNull(ebQueueArn);

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + EB_RULE
                        + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.ses\\\"]}\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"" + EB_RULE + "\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\""
                        + ebQueueArn + "\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS_EB + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-eventbridge",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND", "DELIVERY", "BOUNCE"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(EB_DEFAULT_BUS_ARN))
        .when()
                .post("/v2/email/configuration-sets/" + CS_EB + "/event-destinations")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(19)
    void eventBridge_regularRecipient_routesSendEventToSqsTarget() throws Exception {
        drainEbQueue();
        sendEmailToConfigSet(CS_EB, "recipient@example.com", "eb-evt");

        List<JsonNode> envelopes = receiveEbEvents(1);
        assertEquals(1, envelopes.size());

        JsonNode envelope = envelopes.get(0);
        assertEquals("aws.ses", envelope.path("source").asText());
        assertEquals("Email Sent", envelope.path("detail-type").asText());
        assertEquals("default", envelope.path("event-bus-name").asText());
        // AWS always emits a `resources` array on the envelope, and SesEventPublisher mirrors
        // that contract. SesService derives sourceArn from FromEmailAddress, so this case
        // produces a one-element array carrying the SES identity ARN; the sourceArn-null
        // branch (empty array on the PutEvents entry) is pinned by SesEventPublisherTest.
        assertTrue(envelope.has("resources") && envelope.get("resources").isArray(),
                "envelope must always carry a resources array");
        assertEquals(1, envelope.get("resources").size());
        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/" + SENDER,
                envelope.get("resources").get(0).asText());

        JsonNode detail = envelope.path("detail");
        assertEquals("Send", detail.path("eventType").asText());
        assertEquals(SENDER, detail.path("mail").path("source").asText());
        assertEquals(CS_EB,
                detail.path("mail").path("tags").path("ses:configuration-set").get(0).asText());
    }

    @Test
    @Order(20)
    void eventBridge_successSimulator_routesSendAndDeliveryDetailTypes() throws Exception {
        drainEbQueue();
        sendEmailToConfigSet(CS_EB, "success@simulator.amazonses.com", "eb-evt-success");

        List<JsonNode> envelopes = receiveEbEvents(2);
        assertEquals(2, envelopes.size());
        assertTrue(envelopes.stream().anyMatch(
                e -> "Email Sent".equals(e.path("detail-type").asText())));
        assertTrue(envelopes.stream().anyMatch(
                e -> "Email Delivered".equals(e.path("detail-type").asText())));
    }

    @Test
    @Order(21)
    void eventBridge_disabledEventDestination_skipsPublish() throws Exception {
        String csDisabled = "ses-events-eb-cs-disabled";
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
                      "EventDestinationName": "ed-eventbridge-disabled",
                      "EventDestination": {
                        "Enabled": false,
                        "MatchingEventTypes": ["SEND"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(EB_DEFAULT_BUS_ARN))
        .when()
                .post("/v2/email/configuration-sets/" + csDisabled + "/event-destinations")
        .then()
                .statusCode(200);

        drainEbQueue();
        sendEmailToConfigSet(csDisabled, "recipient@example.com", "eb-disabled");

        List<JsonNode> envelopes = receiveEbEvents(0);
        assertEquals(0, envelopes.size(),
                "disabled event destination should not publish to EventBridge");
    }

    @Test
    @Order(22)
    void eventBridge_unknownBusName_sendStillSucceeds() {
        String csUnknown = "ses-events-eb-cs-unknown";
        String bogusBusArn =
                "arn:aws:events:us-east-1:000000000000:event-bus/does-not-exist";

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + csUnknown + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-eventbridge-unknown",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(bogusBusArn))
        .when()
                .post("/v2/email/configuration-sets/" + csUnknown + "/event-destinations")
        .then()
                .statusCode(200);

        // putEvents records a failed entry for an unknown bus but does not throw — SES send
        // must still return 200.
        sendEmailToConfigSet(csUnknown, "recipient@example.com", "eb-unknown");
    }

    // ============================ CloudWatch =============================

    @Test
    @Order(23)
    void cloudWatch_setupConfigSetAndEventDestination() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS_CW + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-cloudwatch",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND", "DELIVERY", "BOUNCE"],
                        "CloudWatchDestination": {
                          "DimensionConfigurations": [
                            {
                              "DimensionName": "ses:configuration-set",
                              "DimensionValueSource": "messageTag",
                              "DefaultDimensionValue": "unknown"
                            },
                            {
                              "DimensionName": "campaign",
                              "DimensionValueSource": "messageTag",
                              "DefaultDimensionValue": "default-campaign"
                            },
                            {
                              "DimensionName": "X-Custom-Header",
                              "DimensionValueSource": "emailHeader",
                              "DefaultDimensionValue": "default-header"
                            }
                          ]
                        }
                      }
                    }
                    """)
        .when()
                .post("/v2/email/configuration-sets/" + CS_CW + "/event-destinations")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(24)
    void cloudWatch_sendWithTagsAndHeaders_registersSendMetricWithResolvedDimensions() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "ConfigurationSetName": "%s",
                      "EmailTags": [
                        {"Name": "campaign", "Value": "launch"}
                      ],
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "cw-evt"},
                          "Body": {"Text": {"Data": "hi"}},
                          "Headers": [
                            {"Name": "X-Custom-Header", "Value": "header-value"}
                          ]
                        }
                      }
                    }
                    """.formatted(SENDER, CS_CW))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<MetricIdentity> metrics = metricsService.listMetrics(CW_NAMESPACE, "Send", null, CW_REGION);
        assertFalse(metrics.isEmpty(), "expected a Send metric in AWS/SES");
        MetricIdentity send = metrics.stream()
                .filter(m -> cloudWatch_hasDimensions(m, CS_CW, "launch", "header-value"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Send metric with dimensions ses:configuration-set=" + CS_CW
                                + ", campaign=launch, X-Custom-Header=header-value not found: "
                                + metrics));
        assertEquals(3, send.dimensions().size(),
                "expected exactly the three configured dimensions, got: " + send.dimensions());
    }

    @Test
    @Order(25)
    void cloudWatch_sendWithoutTags_fallsBackToDefaultDimensionValue() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["recipient2@example.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "cw-no-tags"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, CS_CW))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<MetricIdentity> metrics = metricsService.listMetrics(CW_NAMESPACE, "Send", null, CW_REGION);
        assertTrue(metrics.stream().anyMatch(m -> cloudWatch_hasDimensions(m, CS_CW,
                "default-campaign", "default-header")),
                "expected Send metric with default-campaign / default-header fallback dimensions, got: "
                        + metrics);
    }

    @Test
    @Order(26)
    void cloudWatch_successSimulator_registersBothSendAndDeliveryMetrics() {
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
                          "Subject": {"Data": "cw-success"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, CS_CW))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<MetricIdentity> sendMetrics = metricsService.listMetrics(CW_NAMESPACE, "Send", null, CW_REGION);
        List<MetricIdentity> deliveryMetrics = metricsService.listMetrics(CW_NAMESPACE, "Delivery", null, CW_REGION);
        assertFalse(sendMetrics.isEmpty(), "Send metric should be present");
        assertFalse(deliveryMetrics.isEmpty(),
                "Delivery metric should be present for success simulator address");
    }

    @Test
    @Order(27)
    void cloudWatch_disabledEventDestination_doesNotPublishMetric() {
        String csDisabled = "ses-events-cw-cs-disabled";
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
                      "EventDestinationName": "ed-cloudwatch-disabled",
                      "EventDestination": {
                        "Enabled": false,
                        "MatchingEventTypes": ["SEND"],
                        "CloudWatchDestination": {
                          "DimensionConfigurations": [
                            {
                              "DimensionName": "ses:configuration-set",
                              "DimensionValueSource": "messageTag",
                              "DefaultDimensionValue": "unknown"
                            }
                          ]
                        }
                      }
                    }
                    """)
        .when()
                .post("/v2/email/configuration-sets/" + csDisabled + "/event-destinations")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "cw-disabled"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, csDisabled))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<MetricIdentity> metrics = metricsService.listMetrics(CW_NAMESPACE, "Send", null, CW_REGION);
        assertTrue(metrics.stream().noneMatch(m -> m.dimensions().stream()
                        .anyMatch(d -> "ses:configuration-set".equals(d.name())
                                && csDisabled.equals(d.value()))),
                "disabled event destination should not register any Send metric for " + csDisabled);
    }

    @Test
    @Order(28)
    void eventBridge_nonDefaultRegion_matchesRegionFilteredRuleAndStampsEnvelopeRegion()
            throws Exception {
        // Pins the SES→EventBridge cross-region path against two regressions:
        //   (a) SesEventPublisher.publishEventBridge must stamp `entry.Region = <bus-region>`,
        //       otherwise EventBridgeService.matchesPattern falls back to the default region
        //       and a rule scoped to eu-west-1 will never match a SES-originated event.
        //   (b) EventBridgeService.buildEventEnvelope (post #1125) must propagate the
        //       PutEvents call's region to the delivered envelope's `region` field, so
        //       downstream consumers see eu-west-1, not us-east-1.
        String region = "eu-west-1";
        String queueName = "ses-events-eb-eu-west-1-queue";
        String ruleName = "ses-events-eb-eu-west-1-rule";
        String csName = "ses-events-eb-eu-west-1-cs";
        String busArn = "arn:aws:events:" + region + ":000000000000:event-bus/default";
        String sqsAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/sqs/aws4_request";
        String eventsAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/events/aws4_request";
        String sesAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/ses/aws4_request";

        String euQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", sqsAuth)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + queueName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(euQueueUrl);

        String euQueueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", sqsAuth)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + euQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        assertNotNull(euQueueArn);

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", eventsAuth)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + ruleName
                        + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.ses\\\"],"
                        + "\\\"region\\\":[\\\"" + region + "\\\"]}\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", eventsAuth)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"" + ruleName + "\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\""
                        + euQueueArn + "\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", sesAuth)
                .body("{\"ConfigurationSetName\":\"" + csName + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", sesAuth)
                .body("""
                    {
                      "EventDestinationName": "ed-eventbridge-eu-west-1",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(busArn))
        .when()
                .post("/v2/email/configuration-sets/" + csName + "/event-destinations")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", sesAuth)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "eb-eu-west-1-evt"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, csName))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);

        List<JsonNode> envelopes = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && envelopes.isEmpty(); attempt++) {
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", sqsAuth)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + euQueueUrl
                            + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when()
                    .post("/")
            .then()
                    .statusCode(200)
                    .extract().response();
            List<String> bodies = r.jsonPath().getList("Messages.Body");
            if (bodies != null) {
                for (String b : bodies) {
                    envelopes.add(MAPPER.readTree(b));
                }
            }
        }
        assertFalse(envelopes.isEmpty(),
                "rule with region=eu-west-1 filter must match SES eu-west-1 event "
                + "(regression: SesEventPublisher must stamp entry.Region)");

        JsonNode envelope = envelopes.get(0);
        assertEquals(region, envelope.path("region").asText(),
                "envelope.region must equal the PutEvents call region "
                + "(regression: EventBridgeService.buildEventEnvelope per #1125)");
        assertEquals("aws.ses", envelope.path("source").asText());
        assertEquals("Email Sent", envelope.path("detail-type").asText());
    }

    @Test
    @Order(29)
    void eventBridge_detailTypePattern_keyedOnAwsSentValue_matches() throws Exception {
        // Structural pin against EventBridge detail-type drift. The rule pattern is keyed
        // on the AWS-correct value "Email Sent" (past tense, per
        // https://docs.aws.amazon.com/eventbridge/latest/ref/events-ref-ses.html). If the
        // publisher regresses to the SNS-style "Email Send" — the wrong form for
        // EventBridge — this rule will not match and no message reaches the SQS target,
        // failing the test without relying on any hard-coded assertion string.
        String queueName = "ses-events-eb-detail-q";
        String ruleName = "ses-events-eb-detail-rule";
        String csName = "ses-events-eb-detail-cs";

        String detailQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + queueName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(detailQueueUrl);

        String detailQueueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + detailQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + ruleName
                        + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.ses\\\"],"
                        + "\\\"detail-type\\\":[\\\"Email Sent\\\"]}\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(EVENTS_CONTENT_TYPE)
                .header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"" + ruleName + "\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\""
                        + detailQueueArn + "\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + csName + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-eventbridge-detail",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(EB_DEFAULT_BUS_ARN))
        .when()
                .post("/v2/email/configuration-sets/" + csName + "/event-destinations")
        .then()
                .statusCode(200);

        sendEmailToConfigSet(csName, "recipient@example.com", "eb-detail-type-pin");

        List<JsonNode> envelopes = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && envelopes.isEmpty(); attempt++) {
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + detailQueueUrl
                            + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when()
                    .post("/")
            .then()
                    .statusCode(200)
                    .extract().response();
            List<String> bodies = r.jsonPath().getList("Messages.Body");
            if (bodies != null) {
                for (String b : bodies) {
                    envelopes.add(MAPPER.readTree(b));
                }
            }
        }
        assertFalse(envelopes.isEmpty(),
                "rule with detail-type=[\"Email Sent\"] must match a SEND emission "
                + "(regression: SesEventPayload.eventBridgeDetailType returning the SNS-style "
                + "'Email Send' for EventBridge would cause this rule to silently drop the event)");
        assertEquals("Email Sent", envelopes.get(0).path("detail-type").asText());
    }

    private static boolean cloudWatch_hasDimensions(MetricIdentity metric, String configurationSet,
                                                    String campaign, String headerValue) {
        return metric.dimensions().stream()
                        .anyMatch(d -> "ses:configuration-set".equals(d.name())
                                && configurationSet.equals(d.value()))
                && metric.dimensions().stream()
                        .anyMatch(d -> "campaign".equals(d.name()) && campaign.equals(d.value()))
                && metric.dimensions().stream()
                        .anyMatch(d -> "X-Custom-Header".equals(d.name())
                                && headerValue.equals(d.value()));
    }

    private void sendEmailToConfigSet(String configSet, String to, String subject) {
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
                          "Subject": {"Data": "%s"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, to, configSet, subject))
        .when()
                .post("/v2/email/outbound-emails")
        .then()
                .statusCode(200);
    }

    private void drainEbQueue() {
        for (int i = 0; i < 5; i++) {
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + ebQueueUrl
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
            deleteEbMessages(handles);
        }
    }

    private List<JsonNode> receiveEbEvents(int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        int maxAttempts = expectedAtLeast > 0 ? 10 : 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (expectedAtLeast > 0 && events.size() >= expectedAtLeast) {
                break;
            }
            Response r = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + ebQueueUrl
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
                events.add(MAPPER.readTree(body));
            }
            deleteEbMessages(handles);
        }
        return events;
    }

    private void deleteEbMessages(List<String> receiptHandles) {
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
                .body("{\"QueueUrl\":\"" + ebQueueUrl + "\",\"Entries\":[" + entries + "]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
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
