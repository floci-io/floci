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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the legacy per-identity feedback notifications configured
 * through {@code SetIdentityNotificationTopic}. A verified identity (or its parent domain) with
 * a Bounce/Complaint/Delivery topic must receive an Amazon SNS notification when a matching
 * event occurs on a send — independently of any configuration set. The delivered payload uses
 * the legacy {@code notificationType} discriminator, not the event-publishing {@code eventType}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityNotificationTopicPublishV1IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sqs/aws4_request";
    private static final String JSON_10 = "application/x-amz-json-1.0";
    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String SENDER = "id-notif-sender@floci.test";
    private static final String NO_TOPIC_SENDER = "id-notif-no-topic@floci.test";
    private static final String FALLBACK_DOMAIN = "id-notif-domain.test";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupQueueTopicAndSubscription() {
        queueUrl = given()
                .contentType(JSON_10)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"ses-id-notif-queue\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(queueUrl);

        queueArn = given()
                .contentType(JSON_10)
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
                .contentType(JSON_10)
                .header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .body("{\"Name\":\"ses-id-notif-topic\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("TopicArn");
        assertNotNull(topicArn);

        given()
                .contentType(JSON_10)
                .header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\""
                        + queueArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void setupIdentitiesAndNotificationTopics() {
        verifyEmailIdentity(SENDER);
        verifyEmailIdentity(NO_TOPIC_SENDER);
        setIdentityNotificationTopic(SENDER, "Bounce", topicArn);
        setIdentityNotificationTopic(SENDER, "Complaint", topicArn);

        verifyDomainIdentity(FALLBACK_DOMAIN);
        setIdentityNotificationTopic(FALLBACK_DOMAIN, "Bounce", topicArn);
    }

    @Test
    @Order(3)
    void bounceSimulator_publishesLegacyBounceNotificationToIdentityTopic() throws Exception {
        drainQueue();
        sendEmail(SENDER, "bounce@simulator.amazonses.com", "id-notif-bounce");

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size(), "expected one Bounce notification on the identity topic");
        JsonNode bounce = notifications.get(0);
        assertEquals("Bounce", bounce.path("notificationType").asText(),
                "legacy identity notification must use notificationType, not eventType");
        assertTrue(bounce.path("eventType").isMissingNode(),
                "legacy identity notification must not carry the event-publishing eventType field");
        JsonNode mail = bounce.path("mail");
        assertEquals(SENDER, mail.path("source").asText());
        assertEquals("bounce@simulator.amazonses.com",
                bounce.path("bounce").path("bouncedRecipients").get(0).path("emailAddress").asText());
        assertTrue(mail.path("tags").isMissingNode(),
                "legacy identity notification mail object must never include a tags field");
        assertTrue(mail.path("headers").isMissingNode(),
                "headers must be omitted when headers-in-notifications is disabled (default)");
        assertTrue(mail.path("commonHeaders").isMissingNode(),
                "commonHeaders must be omitted when headers-in-notifications is disabled (default)");
        assertTrue(mail.path("headersTruncated").isMissingNode(),
                "headersTruncated must be omitted when headers-in-notifications is disabled (default)");
    }

    @Test
    @Order(4)
    void complaintSimulator_publishesLegacyComplaintNotificationToIdentityTopic() throws Exception {
        drainQueue();
        sendEmail(SENDER, "complaint@simulator.amazonses.com", "id-notif-complaint");

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size());
        JsonNode complaint = notifications.get(0);
        assertEquals("Complaint", complaint.path("notificationType").asText());
        assertEquals("complaint@simulator.amazonses.com",
                complaint.path("complaint").path("complainedRecipients").get(0).path("emailAddress").asText());
    }

    @Test
    @Order(5)
    void domainIdentityTopic_appliesToEmailWithoutItsOwnTopic() throws Exception {
        drainQueue();
        sendEmail("anyone@" + FALLBACK_DOMAIN, "bounce@simulator.amazonses.com", "id-notif-domain-fallback");

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size(),
                "an unconfigured email identity must inherit its parent domain's Bounce topic");
        assertEquals("Bounce", notifications.get(0).path("notificationType").asText());
    }

    @Test
    @Order(6)
    void identityWithoutTopic_andNoConfigSet_publishesNothing() throws Exception {
        drainQueue();
        sendEmail(NO_TOPIC_SENDER, "bounce@simulator.amazonses.com", "id-notif-none");

        List<JsonNode> notifications = receiveNotifications(0);
        assertTrue(notifications.isEmpty(),
                "no identity topic and no configuration set should produce no notification");
    }

    @Test
    @Order(7)
    void headersInNotificationsEnabled_includesOriginalHeadersButStillNoTags() throws Exception {
        setHeadersInNotificationsEnabled(SENDER, "Bounce", true);
        drainQueue();
        sendEmail(SENDER, "bounce@simulator.amazonses.com", "id-notif-with-headers");

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size());
        JsonNode mail = notifications.get(0).path("mail");
        assertFalse(mail.path("headers").isMissingNode(),
                "headers must be present once headers-in-notifications is enabled for the type");
        assertEquals("id-notif-with-headers", mail.path("commonHeaders").path("subject").asText());
        assertFalse(mail.path("headersTruncated").asBoolean(true));
        assertTrue(mail.path("tags").isMissingNode(),
                "tags must never appear on a legacy identity notification, even with headers enabled");
    }

    @Test
    @Order(8)
    void displayNameSource_buildsSourceArnFromBareEmail() throws Exception {
        drainQueue();
        sendEmail("Sender Name <" + SENDER + ">", "bounce@simulator.amazonses.com", "id-notif-display-name");

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size());
        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/" + SENDER,
                notifications.get(0).path("mail").path("sourceArn").asText(),
                "sourceArn must be built from the bare email, not the display-name form");
    }

    @Test
    @Order(9)
    void sendRawEmailWithoutSource_resolvesIdentityFromMimeFrom() throws Exception {
        drainQueue();
        String raw = "From: " + SENDER + "\r\n"
                + "To: bounce@simulator.amazonses.com\r\n"
                + "Subject: id-notif-raw-from\r\n\r\nbody";
        String rawB64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=SendRawEmail"
                        + "&RawMessage.Data=" + URLEncoder.encode(rawB64, StandardCharsets.UTF_8)
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        List<JsonNode> notifications = receiveNotifications(1);
        assertEquals(1, notifications.size(),
                "identity topic must fire even when Source is omitted and From comes from the MIME body");
        JsonNode bounce = notifications.get(0);
        assertEquals("Bounce", bounce.path("notificationType").asText());
        assertEquals(SENDER, bounce.path("mail").path("source").asText());
    }

    private void verifyEmailIdentity(String email) {
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=VerifyEmailIdentity&EmailAddress="
                        + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void verifyDomainIdentity(String domain) {
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=VerifyDomainIdentity&Domain="
                        + URLEncoder.encode(domain, StandardCharsets.UTF_8) + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void setIdentityNotificationTopic(String identity, String notificationType, String snsTopic) {
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=SetIdentityNotificationTopic"
                        + "&Identity=" + URLEncoder.encode(identity, StandardCharsets.UTF_8)
                        + "&NotificationType=" + notificationType
                        + "&SnsTopic=" + URLEncoder.encode(snsTopic, StandardCharsets.UTF_8)
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void setHeadersInNotificationsEnabled(String identity, String notificationType, boolean enabled) {
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=SetIdentityHeadersInNotificationsEnabled"
                        + "&Identity=" + URLEncoder.encode(identity, StandardCharsets.UTF_8)
                        + "&NotificationType=" + notificationType
                        + "&Enabled=" + enabled
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void sendEmail(String source, String to, String subject) {
        given()
                .contentType(FORM)
                .header("Authorization", SES_AUTH)
                .body("Action=SendEmail"
                        + "&Source=" + URLEncoder.encode(source, StandardCharsets.UTF_8)
                        + "&Destination.ToAddresses.member.1="
                        + URLEncoder.encode(to, StandardCharsets.UTF_8)
                        + "&Message.Subject.Data=" + URLEncoder.encode(subject, StandardCharsets.UTF_8)
                        + "&Message.Body.Text.Data=hi"
                        + "&Version=2010-12-01")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void drainQueue() {
        for (int i = 0; i < 5; i++) {
            Response r = given()
                    .contentType(JSON_10)
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

    private List<JsonNode> receiveNotifications(int expectedAtLeast) throws Exception {
        List<JsonNode> notifications = new ArrayList<>();
        int maxAttempts = expectedAtLeast > 0 ? 10 : 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (expectedAtLeast > 0 && notifications.size() >= expectedAtLeast) {
                break;
            }
            Response r = given()
                    .contentType(JSON_10)
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
                notifications.add(MAPPER.readTree(snsWrapper.path("Message").asText()));
            }
            deleteMessages(handles);
        }
        return notifications;
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
                .contentType(JSON_10)
                .header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.DeleteMessageBatch")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"Entries\":[" + entries + "]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}
