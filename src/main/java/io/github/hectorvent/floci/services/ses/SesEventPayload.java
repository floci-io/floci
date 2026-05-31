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

    static ObjectNode build(ObjectMapper mapper, String eventType, String messageId, String source,
                            String sourceArn, String sendingAccountId, String subject,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> envelopeDestinations,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, Instant timestamp) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventType", eventTypeLabel(eventType));
        root.set("mail", buildMail(mapper, messageId, source, sourceArn, sendingAccountId,
                subject, toAddresses, ccAddresses, bccAddresses, envelopeDestinations,
                configurationSetName, emailTags, additionalHeaders, timestamp));
        root.set(blockName(eventType),
                buildEventBlock(mapper, eventType, messageId, envelopeDestinations, timestamp));
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
                                              List<String> destination, Instant timestamp) {
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
                ArrayNode bounced = body.putArray("bouncedRecipients");
                for (String d : destination) {
                    if (SimulatorAddresses.isBounce(d)) {
                        ObjectNode br = bounced.addObject();
                        br.put("emailAddress", d.trim());
                    }
                }
                body.put("timestamp", ISO_MILLIS.format(timestamp));
                body.put("feedbackId", "feedback-" + messageId);
            }
            case "COMPLAINT" -> {
                ArrayNode complained = body.putArray("complainedRecipients");
                for (String d : destination) {
                    if (SimulatorAddresses.isComplaint(d)) {
                        ObjectNode cr = complained.addObject();
                        cr.put("emailAddress", d.trim());
                    }
                }
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

    private static String eventTypeLabel(String eventType) {
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
