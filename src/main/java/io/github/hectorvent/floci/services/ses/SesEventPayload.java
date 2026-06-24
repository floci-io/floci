package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
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

    static ObjectNode build(ObjectMapper mapper, String eventType, String messageId, String source,
                            String sourceArn, String sendingAccountId, String subject,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> envelopeDestinations,
                            List<String> suppressionBounceRecipients,
                            List<String> suppressionComplaintRecipients,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, Instant timestamp) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventType", eventTypeLabel(eventType));
        root.set("mail", buildMail(mapper, messageId, source, sourceArn, sendingAccountId,
                subject, toAddresses, ccAddresses, bccAddresses, envelopeDestinations,
                configurationSetName, emailTags, additionalHeaders, timestamp));
        root.set(blockName(eventType),
                buildEventBlock(mapper, eventType, messageId, envelopeDestinations,
                        suppressionBounceRecipients, suppressionComplaintRecipients, timestamp));
        return root;
    }

    private static ObjectNode buildMail(ObjectMapper mapper, String messageId, String source,
                                        String sourceArn, String sendingAccountId,
                                        String subject, List<String> toAddresses,
                                        List<String> ccAddresses, List<String> bccAddresses,
                                        List<String> envelopeDestinations,
                                        String configurationSetName, List<MessageTag> emailTags,
                                        List<MessageHeader> additionalHeaders,
                                        Instant timestamp) {
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
        return mail;
    }

    private static void addHeader(ArrayNode headers, ObjectMapper mapper, String name, String value) {
        ObjectNode h = mapper.createObjectNode();
        h.put("name", name);
        h.put("value", value);
        headers.add(h);
    }

    private static ObjectNode buildEventBlock(ObjectMapper mapper, String eventType, String messageId,
                                              List<String> destination,
                                              List<String> suppressionBounceRecipients,
                                              List<String> suppressionComplaintRecipients,
                                              Instant timestamp) {
        ObjectNode body = mapper.createObjectNode();
        switch (eventType) {
            case "DELIVERY" -> {
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("processingTimeMillis", 0);
                ArrayNode recipients = body.putArray("recipients");
                for (String d : destination) {
                    if (SimulatorAddresses.isSuccess(d)) {
                        recipients.add(d.trim());
                    }
                }
                body.put("smtpResponse", "250 ok");
                body.put("reportingMTA", "floci");
            }
            case "BOUNCE" -> {
                body.put("bounceType", "Permanent");
                body.put("bounceSubType", "General");
                emitDedupedRecipientObjects(body.putArray("bouncedRecipients"),
                        destination, SimulatorAddresses::isBounce,
                        suppressionBounceRecipients);
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("feedbackId", "feedback-" + messageId);
            }
            case "COMPLAINT" -> {
                emitDedupedRecipientObjects(body.putArray("complainedRecipients"),
                        destination, SimulatorAddresses::isComplaint,
                        suppressionComplaintRecipients);
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("feedbackId", "feedback-" + messageId);
            }
            case "REJECT" -> body.put("reason", "Bad content");
            default -> {
                // SEND and other not-yet-modelled event types: empty block.
            }
        }
        return body;
    }

    /**
     * Emit `{emailAddress: ...}` objects into {@code arr} for every recipient that either
     * matches {@code simulatorPredicate} in {@code envelope} or is listed in
     * {@code suppressionRecipients}. Addresses are trimmed and deduplicated by trimmed
     * form so the same address never appears twice when both inputs claim it.
     */
    private static void emitDedupedRecipientObjects(ArrayNode arr,
                                                    List<String> envelope,
                                                    java.util.function.Predicate<String> simulatorPredicate,
                                                    List<String> suppressionRecipients) {
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        if (envelope != null) {
            for (String d : envelope) {
                if (d != null && simulatorPredicate.test(d) && emitted.add(d.trim())) {
                    arr.addObject().put("emailAddress", d.trim());
                }
            }
        }
        if (suppressionRecipients != null) {
            for (String d : suppressionRecipients) {
                if (d != null && emitted.add(d.trim())) {
                    arr.addObject().put("emailAddress", d.trim());
                }
            }
        }
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
