package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ses.SesRecipientEvent.Cause;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SesEventPayload}'s JSON shape, mirroring the AWS SES
 * SNS notification format documented at
 * https://docs.aws.amazon.com/ses/latest/dg/event-publishing-retrieving-sns-contents.html.
 */
class SesEventPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant ts = Instant.parse("2026-05-30T00:00:00Z");

    @Test
    void send_buildsMailBlockWithCommonHeadersAndTags() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "from@example.com", "arn:aws:ses:us-east-1:000000000000:identity/from@example.com", "000000000000", "Hello", List.of("to@example.com"), List.of("cc@example.com"), List.of(), List.of("to@example.com", "cc@example.com"), "my-cs", List.of(), List.of(), ts);

        assertEquals("Send", node.get("eventType").asText());
        assertTrue(node.has("send"));
        ObjectNode mail = (ObjectNode) node.get("mail");
        assertEquals("from@example.com", mail.get("source").asText());
        assertEquals("msg-1", mail.get("messageId").asText());
        assertEquals("2026-05-30T00:00:00.000Z", mail.get("timestamp").asText());
        assertEquals("000000000000", mail.get("sendingAccountId").asText());
        assertEquals("to@example.com", mail.get("destination").get(0).asText());

        // headers array contains From, To, Cc, Subject
        ObjectNode commonHeaders = (ObjectNode) mail.get("commonHeaders");
        assertEquals("msg-1", commonHeaders.get("messageId").asText());
        assertEquals("Sat, 30 May 2026 00:00:00 +0000", commonHeaders.get("date").asText());
        assertEquals("from@example.com", commonHeaders.get("from").get(0).asText());
        assertEquals("to@example.com", commonHeaders.get("to").get(0).asText());
        assertEquals("cc@example.com", commonHeaders.get("cc").get(0).asText());
        assertFalse(commonHeaders.has("bcc"));
        assertEquals("Hello", commonHeaders.get("subject").asText());

        // headers array
        assertTrue(mail.get("headers").isArray());
        String headers = mail.get("headers").toString();
        assertTrue(headers.contains("\"name\":\"From\""));
        assertTrue(headers.contains("\"name\":\"To\""));
        assertTrue(headers.contains("\"name\":\"Cc\""));
        assertTrue(headers.contains("\"name\":\"Subject\""));

        // tags
        assertEquals("my-cs", mail.get("tags").get("ses:configuration-set").get(0).asText());
    }

    @Test
    void delivery_includesRecipientsAndSmtpResponse() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("DELIVERY", Cause.SIMULATOR, List.of("success@simulator.amazonses.com")), "msg-1", "from@example.com", null, "000000000000", "", List.of("success@simulator.amazonses.com"), List.of(), List.of(), List.of("success@simulator.amazonses.com"), "cs", List.of(), List.of(), ts);

        assertEquals("Delivery", node.get("eventType").asText());
        ObjectNode delivery = (ObjectNode) node.get("delivery");
        assertEquals("success@simulator.amazonses.com",
                delivery.get("recipients").get(0).asText());
        assertNotNull(delivery.get("smtpResponse"));
        assertNotNull(delivery.get("reportingMTA"));
    }

    @Test
    void bounce_includesBouncedRecipientsAndPermanentType() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("BOUNCE", Cause.SIMULATOR, List.of("bounce@simulator.amazonses.com")), "msg-1", "from@example.com", null, "000000000000", "", List.of("bounce@simulator.amazonses.com"), List.of(), List.of(), List.of("bounce@simulator.amazonses.com"), "cs", List.of(), List.of(), ts);

        assertEquals("Bounce", node.get("eventType").asText());
        ObjectNode bounce = (ObjectNode) node.get("bounce");
        assertEquals("Permanent", bounce.get("bounceType").asText());
        assertEquals("General", bounce.get("bounceSubType").asText());
        assertEquals("bounce@simulator.amazonses.com",
                bounce.get("bouncedRecipients").get(0).get("emailAddress").asText());
    }

    @Test
    void complaint_includesComplainedRecipients() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("COMPLAINT", Cause.SIMULATOR, List.of("complaint@simulator.amazonses.com")), "msg-1", "from@example.com", null, "000000000000", "", List.of("complaint@simulator.amazonses.com"), List.of(), List.of(), List.of("complaint@simulator.amazonses.com"), "cs", List.of(), List.of(), ts);

        assertEquals("Complaint", node.get("eventType").asText());
        assertEquals("complaint@simulator.amazonses.com",
                node.get("complaint").get("complainedRecipients").get(0).get("emailAddress").asText());
        assertEquals(node.get("complaint").get("timestamp").asText(),
                node.get("complaint").get("arrivalDate").asText());
    }

    @Test
    void send_emailTagsAppearInMailTagsAlongsideConfigurationSet() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "from@example.com", null, "000000000000", "Hello", List.of("to@example.com"), List.of(), List.of(), List.of("to@example.com"), "my-cs", List.of(new MessageTag("campaign", "launch"), new MessageTag("env", "prod")), List.of(), ts);

        ObjectNode tags = (ObjectNode) node.get("mail").get("tags");
        assertEquals("my-cs", tags.get("ses:configuration-set").get(0).asText());
        assertEquals("launch", tags.get("campaign").get(0).asText());
        assertEquals("prod", tags.get("env").get(0).asText());
    }

    @Test
    void send_emailTagsTolerateNullValueAndDuplicateKey() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "from@example.com", null, "000000000000", "", List.of("to@example.com"), List.of(), List.of(), List.of("to@example.com"), "cs", List.of(new MessageTag("k", null), new MessageTag("k", "v2")), List.of(), ts);

        var arr = node.get("mail").get("tags").get("k");
        assertEquals(2, arr.size(), "duplicate keys append into the same array");
        assertEquals("", arr.get(0).asText());
        assertEquals("v2", arr.get(1).asText());
    }

    @Test
    void send_additionalHeadersAppendToMailHeadersAfterAutoHeaders() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "from@example.com", null, "000000000000", "Hi", List.of("to@example.com"), List.of(), List.of(), List.of("to@example.com"), "cs", List.of(), List.of(
                        new MessageHeader("X-Mailer", "floci"),
                        new MessageHeader("List-Unsubscribe", "<mailto:u@example.com>")), ts);

        ArrayNode headers = (ArrayNode) node.get("mail").get("headers");
        // From, To, Subject auto-emitted first; then user headers in order.
        assertEquals("From", headers.get(0).get("name").asText());
        assertEquals("To", headers.get(1).get("name").asText());
        assertEquals("Subject", headers.get(2).get("name").asText());
        assertEquals("X-Mailer", headers.get(3).get("name").asText());
        assertEquals("floci", headers.get(3).get("value").asText());
        assertEquals("List-Unsubscribe", headers.get(4).get("name").asText());
    }

    @Test
    void send_additionalHeadersSkipNullOrBlankName() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "from@example.com", null, "000000000000", "Hi", List.of("to@example.com"), List.of(), List.of(), List.of("to@example.com"), "cs", List.of(), List.of(
                        new MessageHeader(null, "ignored"),
                        new MessageHeader("", "ignored-too"),
                        new MessageHeader("X-Real", "ok")), ts);

        ArrayNode headers = (ArrayNode) node.get("mail").get("headers");
        // Only the auto headers (From/To/Subject) and the single valid X-Real.
        boolean hasXReal = false;
        for (com.fasterxml.jackson.databind.JsonNode h : headers) {
            String name = h.path("name").asText();
            assertFalse(name.isBlank(), "no blank-named header should be emitted");
            if ("X-Real".equals(name)) {
                hasXReal = true;
            }
        }
        assertTrue(hasXReal);
    }

    @Test
    void bounce_forAccountSuppression_rendersOnlyItsOwnRecipientsWithTheSuppressionSubtype() {
        // The envelope carries both a simulator bounce and a suppressed address, but this event is
        // the suppression one: the simulator recipient belongs to a separate BOUNCE event.
        ObjectNode node = SesEventPayload.build(mapper,
                SesRecipientEvent.of("BOUNCE", Cause.ACCOUNT_SUPPRESSION, List.of("suppressed-bounce@example.com")),
                "msg-1", "from@example.com", null, "000000000000", "",
                List.of("bounce@simulator.amazonses.com", "suppressed-bounce@example.com"), List.of(), List.of(),
                List.of("bounce@simulator.amazonses.com", "suppressed-bounce@example.com"),
                "cs", List.of(), List.of(), ts);

        ObjectNode bounce = (ObjectNode) node.get("bounce");
        assertEquals("OnAccountSuppressionList", bounce.get("bounceSubType").asText());
        assertEquals(1, bounce.get("bouncedRecipients").size());
        ObjectNode recipient = (ObjectNode) bounce.get("bouncedRecipients").get(0);
        assertEquals("suppressed-bounce@example.com", recipient.get("emailAddress").asText());
        assertEquals("failed", recipient.get("action").asText());
        assertEquals("5.1.1", recipient.get("status").asText());
        assertEquals(SesEventPayload.ACCOUNT_SUPPRESSION_DIAGNOSTIC, recipient.get("diagnosticCode").asText());
        assertEquals(2, node.get("mail").get("destination").size(), "mail.destination keeps the full envelope");
    }

    @Test
    void complaint_forAccountSuppression_rendersOnlyItsOwnRecipientsWithTheSuppressionSubtype() {
        ObjectNode node = SesEventPayload.build(mapper,
                SesRecipientEvent.of("COMPLAINT", Cause.ACCOUNT_SUPPRESSION, List.of("suppressed-complaint@example.com")),
                "msg-1", "from@example.com", null, "000000000000", "",
                List.of("complaint@simulator.amazonses.com", "suppressed-complaint@example.com"), List.of(), List.of(),
                List.of("complaint@simulator.amazonses.com", "suppressed-complaint@example.com"),
                "cs", List.of(), List.of(), ts);

        ObjectNode complaint = (ObjectNode) node.get("complaint");
        assertEquals("OnAccountSuppressionList", complaint.get("complaintSubType").asText());
        assertEquals(1, complaint.get("complainedRecipients").size());
        assertEquals("suppressed-complaint@example.com",
                complaint.get("complainedRecipients").get(0).get("emailAddress").asText());
    }

    @Test
    void bounce_listManagementOptOutIsAGeneralBounceCarryingOnlyTheAddress() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("BOUNCE", Cause.LIST_MANAGEMENT, List.of("unsub@example.com")), "msg-1", "from@example.com", null, "000000000000", "", List.of("unsub@example.com"), List.of(), List.of(), List.of("unsub@example.com"), "cs", List.of(), List.of(), ts);

        ObjectNode bounce = (ObjectNode) node.get("bounce");
        assertEquals("General", bounce.get("bounceSubType").asText());
        ObjectNode entry = (ObjectNode) bounce.get("bouncedRecipients").get(0);
        assertEquals("unsub@example.com", entry.get("emailAddress").asText());
        assertFalse(entry.has("diagnosticCode"));
    }

    @Test
    void feedbackIdsDifferBetweenTheEventsOfOneSend() {
        List<String> envelope = List.of("bounce@simulator.amazonses.com", "blocked@example.com");
        ObjectNode simulator = SesEventPayload.build(mapper, SesRecipientEvent.of("BOUNCE", Cause.SIMULATOR, List.of("bounce@simulator.amazonses.com")), "msg-1", "from@example.com", null, "000000000000", "", envelope, List.of(), List.of(), envelope, "cs", List.of(), List.of(), ts);
        ObjectNode suppressed = SesEventPayload.build(mapper, SesRecipientEvent.of("BOUNCE", Cause.ACCOUNT_SUPPRESSION, List.of("blocked@example.com")), "msg-1", "from@example.com", null, "000000000000", "", envelope, List.of(), List.of(), envelope, "cs", List.of(), List.of(), ts);

        assertNotEquals(simulator.get("bounce").get("feedbackId").asText(),
                suppressed.get("bounce").get("feedbackId").asText());
    }

    @Test
    void feedbackIdIsTheSameOnTheEventAndOnTheIdentityNotificationOfOneOutcome() {
        SesRecipientEvent bounce = SesRecipientEvent.of("BOUNCE", Cause.SIMULATOR, List.of("bounce@simulator.amazonses.com"));
        List<String> envelope = List.of("bounce@simulator.amazonses.com");
        ObjectNode event = SesEventPayload.build(mapper, bounce, "msg-1", "from@example.com", null, "000000000000", "", envelope, List.of(), List.of(), envelope, "cs", List.of(), List.of(), ts);
        ObjectNode notification = SesEventPayload.buildIdentityNotification(mapper, bounce, "msg-1", "from@example.com", null, "000000000000", "", envelope, List.of(), List.of(), envelope, List.of(), ts, false);

        assertEquals(event.get("bounce").get("feedbackId").asText(),
                notification.get("bounce").get("feedbackId").asText());
    }

    @Test
    void reject_hasReason() {
        ObjectNode node = SesEventPayload.build(mapper, SesRecipientEvent.of("REJECT", Cause.CONTENT_REJECTED, List.of()), "msg-1", "from@example.com", null, "000000000000", "", List.of("suppressionlist@simulator.amazonses.com"), List.of(), List.of(), List.of("suppressionlist@simulator.amazonses.com"), "cs", List.of(), List.of(), ts);

        assertEquals("Reject", node.get("eventType").asText());
        assertNotNull(node.get("reject").get("reason"));
    }

    /**
     * AWS CloudWatch metric names for SES events are mostly the same as the SNS
     * {@code eventType} value, except {@code RENDERING_FAILURE} is emitted as
     * {@code RenderingFailure} (no space) on CW but {@code Rendering Failure}
     * (with space) on SNS — verified directly against real AWS by sending a
     * templated email with a missing {@code TemplateData} key through a CS with a
     * CloudWatch event destination, then capturing the new metric name from
     * {@code aws cloudwatch list-metrics --namespace AWS/SES}. Pin both spellings
     * so a future cleanup of either accessor cannot silently regress the other.
     */
    @Test
    void cloudWatchMetricName_matchesAwsCwSpelling() {
        assertEquals("Send",              SesEventPayload.cloudWatchMetricName("SEND"));
        assertEquals("Reject",            SesEventPayload.cloudWatchMetricName("REJECT"));
        assertEquals("Bounce",            SesEventPayload.cloudWatchMetricName("BOUNCE"));
        assertEquals("Complaint",         SesEventPayload.cloudWatchMetricName("COMPLAINT"));
        assertEquals("Delivery",          SesEventPayload.cloudWatchMetricName("DELIVERY"));
        assertEquals("Open",              SesEventPayload.cloudWatchMetricName("OPEN"));
        assertEquals("Click",             SesEventPayload.cloudWatchMetricName("CLICK"));
        // Verified against real AWS — no space, unlike the SNS spelling.
        assertEquals("RenderingFailure",  SesEventPayload.cloudWatchMetricName("RENDERING_FAILURE"));
        assertEquals("DeliveryDelay",     SesEventPayload.cloudWatchMetricName("DELIVERY_DELAY"));
        assertEquals("Subscription",      SesEventPayload.cloudWatchMetricName("SUBSCRIPTION"));
    }

    /**
     * EventBridge uses a separate past-tense vocabulary for {@code detail-type}, distinct
     * from the SNS notification {@code eventType} value. Pin every mapping against
     * https://docs.aws.amazon.com/eventbridge/latest/ref/events-ref-ses.html so a
     * future cleanup of {@link SesEventPayload#eventTypeLabel} cannot accidentally
     * regress the EventBridge value (which would silently break customer rules
     * keyed on the AWS detail-type names).
     */
    @Test
    void eventBridgeDetailType_matchesAwsPastTenseVocabulary() {
        assertEquals("Email Sent",                SesEventPayload.eventBridgeDetailType("SEND"));
        assertEquals("Email Rejected",            SesEventPayload.eventBridgeDetailType("REJECT"));
        assertEquals("Email Bounced",             SesEventPayload.eventBridgeDetailType("BOUNCE"));
        assertEquals("Email Complaint Received",  SesEventPayload.eventBridgeDetailType("COMPLAINT"));
        assertEquals("Email Delivered",           SesEventPayload.eventBridgeDetailType("DELIVERY"));
        assertEquals("Email Opened",              SesEventPayload.eventBridgeDetailType("OPEN"));
        assertEquals("Email Clicked",             SesEventPayload.eventBridgeDetailType("CLICK"));
        assertEquals("Email Rendering Failed",    SesEventPayload.eventBridgeDetailType("RENDERING_FAILURE"));
        assertEquals("Email Delivery Delayed",    SesEventPayload.eventBridgeDetailType("DELIVERY_DELAY"));
        assertEquals("Email Subscribed",          SesEventPayload.eventBridgeDetailType("SUBSCRIPTION"));
    }
}
