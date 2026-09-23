package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Builds the AWS SES event-publishing JSON for a single sent message.
 *
 * <p>The output shape matches the SES SNS notification format documented at
 * https://docs.aws.amazon.com/ses/latest/dg/event-publishing-retrieving-sns-contents.html
 * — an outer {@code eventType} plus a {@code mail} object and an event-type
 * specific body block.
 */
final class SesEventPayload {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter RFC_5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private SesEventPayload() {}

    static ObjectNode build(ObjectMapper mapper, SesRecipientEvent event, String messageId,
                            String source, String sourceArn, String sendingAccountId, String subject,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> envelopeDestinations,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, Instant timestamp) {
        return buildRoot(mapper, "eventType", event, messageId, source, sourceArn,
                sendingAccountId, subject, toAddresses, ccAddresses, bccAddresses,
                envelopeDestinations, configurationSetName, emailTags, additionalHeaders, timestamp,
                true, true);
    }

    /**
     * Builds the legacy Amazon SNS notification JSON delivered to the per-identity feedback
     * topics configured through {@code SetIdentityNotificationTopic}, matching the format at
     * https://docs.aws.amazon.com/ses/latest/dg/notification-contents.html. It shares the
     * {@code mail}/{@code bounce}/{@code complaint}/{@code delivery} blocks with the
     * event-publishing payload from {@link #build} but differs in three spec-mandated ways:
     * the top-level discriminator is named {@code notificationType} rather than {@code eventType};
     * the {@code mail} object never carries a {@code tags} field; and the original headers
     * ({@code headers}/{@code commonHeaders}/{@code headersTruncated}) appear only when the
     * identity has headers-in-notifications enabled for the type ({@code includeHeaders}). Only
     * the {@code BOUNCE}, {@code COMPLAINT}, and {@code DELIVERY} event types are ever published
     * through this path.
     */
    static ObjectNode buildIdentityNotification(ObjectMapper mapper, SesRecipientEvent event,
                            String messageId, String source, String sourceArn,
                            String sendingAccountId, String subject,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> envelopeDestinations,
                            List<MessageHeader> additionalHeaders, Instant timestamp,
                            boolean includeHeaders) {
        return buildRoot(mapper, "notificationType", event, messageId, source, sourceArn,
                sendingAccountId, subject, toAddresses, ccAddresses, bccAddresses,
                envelopeDestinations, null, null, additionalHeaders, timestamp, includeHeaders, false);
    }

    private static ObjectNode buildRoot(ObjectMapper mapper, String typeField, SesRecipientEvent event,
                            String messageId, String source, String sourceArn,
                            String sendingAccountId, String subject,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> envelopeDestinations,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, Instant timestamp,
                            boolean includeHeaders, boolean includeTags) {
        ObjectNode root = mapper.createObjectNode();
        root.put(typeField, eventTypeLabel(event.eventType()));
        root.set("mail", buildMail(mapper, messageId, source, sourceArn, sendingAccountId,
                subject, toAddresses, ccAddresses, bccAddresses, envelopeDestinations,
                configurationSetName, emailTags, additionalHeaders, timestamp,
                includeHeaders, includeTags));
        root.set(blockName(event.eventType()), buildEventBlock(mapper, event, messageId, timestamp));
        return root;
    }

    private static ObjectNode buildMail(ObjectMapper mapper, String messageId, String source,
                                        String sourceArn, String sendingAccountId,
                                        String subject, List<String> toAddresses,
                                        List<String> ccAddresses, List<String> bccAddresses,
                                        List<String> envelopeDestinations,
                                        String configurationSetName, List<MessageTag> emailTags,
                                        List<MessageHeader> additionalHeaders,
                                        Instant timestamp, boolean includeHeaders,
                                        boolean includeTags) {
        ObjectNode mail = mapper.createObjectNode();
        mail.put("timestamp", ISO_MILLIS.format(timestamp));
        if (source != null && !source.isBlank()) {
            mail.put("source", source);
        }
        if (sourceArn != null) {
            mail.put("sourceArn", sourceArn);
        }
        mail.put("sendingAccountId", sendingAccountId);
        mail.put("messageId", messageId);
        ArrayNode dest = mail.putArray("destination");
        for (String d : envelopeDestinations) {
            dest.add(d);
        }
        if (includeHeaders) {
            mail.put("headersTruncated", false);
            ArrayNode headers = mail.putArray("headers");
            if (source != null && !source.isBlank()) {
                addHeader(headers, mapper, "From", source);
            }
            if (toAddresses != null && !toAddresses.isEmpty()) {
                addHeader(headers, mapper, "To", String.join(", ", toAddresses));
            }
            if (ccAddresses != null && !ccAddresses.isEmpty()) {
                addHeader(headers, mapper, "Cc", String.join(", ", ccAddresses));
            }
            if (subject != null && !subject.isEmpty()) {
                addHeader(headers, mapper, "Subject", subject);
            }
            if (additionalHeaders != null) {
                for (MessageHeader h : additionalHeaders) {
                    if (h == null || h.name() == null || h.name().isBlank()) {
                        continue;
                    }
                    addHeader(headers, mapper, h.name(), h.value() == null ? "" : h.value());
                }
            }
            ObjectNode common = mail.putObject("commonHeaders");
            common.put("messageId", messageId);
            common.put("date", RFC_5322_DATE.format(timestamp));
            ArrayNode fromArr = common.putArray("from");
            if (source != null && !source.isBlank()) {
                fromArr.add(source);
            }
            ArrayNode toArr = common.putArray("to");
            if (toAddresses != null) {
                for (String a : toAddresses) {
                    toArr.add(a);
                }
            }
            if (ccAddresses != null && !ccAddresses.isEmpty()) {
                ArrayNode ccArr = common.putArray("cc");
                for (String a : ccAddresses) {
                    ccArr.add(a);
                }
            }
            if (bccAddresses != null && !bccAddresses.isEmpty()) {
                ArrayNode bccArr = common.putArray("bcc");
                for (String a : bccAddresses) {
                    bccArr.add(a);
                }
            }
            if (subject != null && !subject.isEmpty()) {
                common.put("subject", subject);
            }
        }
        if (includeTags) {
            ObjectNode tags = mail.putObject("tags");
            if (configurationSetName != null) {
                ArrayNode csTag = tags.putArray("ses:configuration-set");
                csTag.add(configurationSetName);
            }
            if (emailTags != null) {
                for (MessageTag t : emailTags) {
                    if (t == null || t.name() == null) {
                        continue;
                    }
                    ArrayNode arr = tags.has(t.name())
                            ? (ArrayNode) tags.get(t.name())
                            : tags.putArray(t.name());
                    arr.add(t.value() == null ? "" : t.value());
                }
            }
        }
        return mail;
    }

    private static void addHeader(ArrayNode headers, ObjectMapper mapper, String name, String value) {
        ObjectNode h = mapper.createObjectNode();
        h.put("name", name);
        h.put("value", value);
        headers.add(h);
    }

    // Probed against real SES on 2026-09-21: an account-suppressed recipient bounces with this
    // subtype and diagnostic, or is complained about with this subtype and no feedback type, while
    // suppressionlist@simulator is an ordinary General bounce whose diagnostic names the suppression.
    static final String ON_ACCOUNT_SUPPRESSION_LIST = "OnAccountSuppressionList";
    static final String ACCOUNT_SUPPRESSION_DIAGNOSTIC = "Amazon SES did not send the message to this "
            + "address because it is on the suppression list for your account. For more information "
            + "about removing addresses from the suppression list, see the Amazon SES Developer Guide "
            + "at https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html";

    private static ObjectNode buildEventBlock(ObjectMapper mapper, SesRecipientEvent event,
                                              String messageId, Instant timestamp) {
        ObjectNode body = mapper.createObjectNode();
        switch (event.eventType()) {
            case "DELIVERY" -> {
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("processingTimeMillis", 0);
                ArrayNode recipients = body.putArray("recipients");
                for (String recipient : event.recipients()) {
                    recipients.add(recipient);
                }
                body.put("smtpResponse", "250 ok");
                body.put("reportingMTA", "floci");
            }
            case "BOUNCE" -> {
                boolean suppressed = event.cause() == SesRecipientEvent.Cause.ACCOUNT_SUPPRESSION;
                body.put("bounceType", "Permanent");
                body.put("bounceSubType", suppressed ? ON_ACCOUNT_SUPPRESSION_LIST : "General");
                ArrayNode bounced = body.putArray("bouncedRecipients");
                for (String recipient : event.recipients()) {
                    ObjectNode entry = bounced.addObject();
                    entry.put("emailAddress", recipient);
                    // The shape of a list-management opt-out bounce has not been probed, so it
                    // carries only the address, as it did before the per-cause split.
                    if (event.cause() != SesRecipientEvent.Cause.LIST_MANAGEMENT) {
                        entry.put("action", "failed");
                        entry.put("status", "5.1.1");
                        entry.put("diagnosticCode", bounceDiagnosticCode(recipient, suppressed));
                    }
                }
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("feedbackId", feedbackId(messageId, event));
            }
            case "COMPLAINT" -> {
                if (event.cause() == SesRecipientEvent.Cause.ACCOUNT_SUPPRESSION) {
                    body.put("complaintSubType", ON_ACCOUNT_SUPPRESSION_LIST);
                }
                ArrayNode complained = body.putArray("complainedRecipients");
                for (String recipient : event.recipients()) {
                    complained.addObject().put("emailAddress", recipient);
                }
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("arrivalDate", ISO_MILLIS.format(timestamp));
                body.put("feedbackId", feedbackId(messageId, event));
            }
            case "REJECT" -> body.put("reason", SesRecipientEvents.CONTENT_REJECT_REASON);
            default -> {
                // SEND and other not-yet-modelled event types: empty block.
            }
        }
        return body;
    }

    // SES gives every bounce and complaint notification its own id, so the events split from one
    // send (a simulator bounce and a suppression bounce) never share one; the id is derived rather
    // than random so the configuration-set event and the identity notification of one outcome agree.
    private static String feedbackId(String messageId, SesRecipientEvent event) {
        return "feedback-" + messageId + "-" + event.eventType().toLowerCase(Locale.ROOT) + "-"
                + event.cause().name().toLowerCase(Locale.ROOT);
    }

    static String bounceDiagnosticCode(String recipient, boolean suppressed) {
        if (suppressed) {
            return ACCOUNT_SUPPRESSION_DIAGNOSTIC;
        }
        if (SimulatorAddresses.isSuppressionList(recipient)) {
            return "smtp; 550 5.1.1 As requested: user unknown (suppressed address: " + recipient + ")";
        }
        // The simulator documents bounce@ as an SMTP 550 5.1.1 "Unknown User" response.
        return "smtp; 550 5.1.1 user unknown";
    }

    static String eventTypeLabel(String eventType) {
        return switch (eventType) {
            case "SEND" -> "Send";
            case "REJECT" -> "Reject";
            case "BOUNCE" -> "Bounce";
            case "COMPLAINT" -> "Complaint";
            case "DELIVERY" -> "Delivery";
            case "OPEN" -> "Open";
            case "CLICK" -> "Click";
            case "RENDERING_FAILURE" -> "Rendering Failure";
            case "DELIVERY_DELAY" -> "DeliveryDelay";
            case "SUBSCRIPTION" -> "Subscription";
            default -> eventType;
        };
    }

    /**
     * Returns the CloudWatch {@code MetricName} value AWS SES uses for an event.
     * Mostly matches {@link #eventTypeLabel}, but at least one event type has a
     * different spelling on CloudWatch than on the SNS payload's {@code eventType}
     * field, and they must not be unified through {@code eventTypeLabel} or the SNS
     * payload regresses to a non-AWS value:
     *   - {@code RENDERING_FAILURE} → CW {@code "RenderingFailure"} (no space)
     *     vs SNS {@code "Rendering Failure"} (with space) — verified against real
     *     AWS via a CW event destination + a templated send with a missing
     *     {@code TemplateData} key.
     * {@code DELIVERY_DELAY} is not directly observed yet (sandbox cannot easily
     * trigger SES backoff); kept as {@code "DeliveryDelay"} pending verification.
     */
    static String cloudWatchMetricName(String eventType) {
        return switch (eventType) {
            case "SEND" -> "Send";
            case "REJECT" -> "Reject";
            case "BOUNCE" -> "Bounce";
            case "COMPLAINT" -> "Complaint";
            case "DELIVERY" -> "Delivery";
            case "OPEN" -> "Open";
            case "CLICK" -> "Click";
            case "RENDERING_FAILURE" -> "RenderingFailure";
            case "DELIVERY_DELAY" -> "DeliveryDelay";
            case "SUBSCRIPTION" -> "Subscription";
            default -> eventType;
        };
    }

    /**
     * Returns the EventBridge {@code detail-type} value for a SES event. AWS uses a
     * separate past-tense vocabulary on EventBridge (e.g. {@code Email Sent}) that
     * differs from the SNS notification {@code eventType} value (e.g. {@code Send})
     * — a rule pattern keyed on the EventBridge value will not match the SNS value.
     * Reference: https://docs.aws.amazon.com/eventbridge/latest/ref/events-ref-ses.html
     */
    static String eventBridgeDetailType(String eventType) {
        return switch (eventType) {
            case "SEND" -> "Email Sent";
            case "REJECT" -> "Email Rejected";
            case "BOUNCE" -> "Email Bounced";
            case "COMPLAINT" -> "Email Complaint Received";
            case "DELIVERY" -> "Email Delivered";
            case "OPEN" -> "Email Opened";
            case "CLICK" -> "Email Clicked";
            case "RENDERING_FAILURE" -> "Email Rendering Failed";
            case "DELIVERY_DELAY" -> "Email Delivery Delayed";
            case "SUBSCRIPTION" -> "Email Subscribed";
            default -> "Email " + eventType;
        };
    }

    private static String blockName(String eventType) {
        return switch (eventType) {
            case "SEND" -> "send";
            case "REJECT" -> "reject";
            case "BOUNCE" -> "bounce";
            case "COMPLAINT" -> "complaint";
            case "DELIVERY" -> "delivery";
            case "RENDERING_FAILURE" -> "failure";
            case "DELIVERY_DELAY" -> "deliveryDelay";
            default -> eventType.toLowerCase(Locale.ROOT);
        };
    }
}
