package io.floci.aws.services.ses.mime;

import io.floci.aws.services.ses.model.MessageTag;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.stream.Field;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads a {@code RawMessage.Data} blob: decodes it, parses it once and extracts the headers the
 * send path acts on.
 */
public final class SesRawMessage {

    private static final Logger LOG = Logger.getLogger(SesRawMessage.class);

    /** SES control header naming the configuration set to apply to a raw send. */
    public static final String HEADER_CONFIGURATION_SET = "X-SES-CONFIGURATION-SET";
    /** SES control header carrying {@code name=value} message tags for a raw send. */
    public static final String HEADER_MESSAGE_TAGS = "X-SES-MESSAGE-TAGS";

    private static final String CONTROL_HEADER_PREFIX = "X-SES-";
    private static final String HEADER_RETURN_PATH = "Return-Path";
    private static final String HEADER_SOURCE_ARN = "X-SES-SOURCE-ARN";
    private static final String HEADER_FROM_ARN = "X-SES-FROM-ARN";
    private static final String HEADER_RETURN_PATH_ARN = "X-SES-RETURN-PATH-ARN";

    private SesRawMessage() {
    }

    /**
     * Structured headers extracted from a raw RFC 5322 MIME message: the first From address, the
     * subject, separate To / Cc / Bcc address lists, the {@code Return-Path} envelope sender, the
     * {@code X-SES-CONFIGURATION-SET} / {@code X-SES-MESSAGE-TAGS} control headers, every
     * mailbox the From header names, the Sender mailbox, and the sending-authorization headers
     * {@code X-SES-SOURCE-ARN}, {@code X-SES-FROM-ARN} and {@code X-SES-RETURN-PATH-ARN}. Of a
     * header given more than once, only the first is read, as SES does for the X-headers. Empty
     * fields when the corresponding header is missing.
     */
    public record Headers(String from, String subject,
                          List<String> to, List<String> cc, List<String> bcc,
                          String returnPath, String configurationSet,
                          List<MessageTag> messageTags, List<String> fromAddresses,
                          String sender, String sourceArn, String fromArn,
                          String returnPathArn) {
        public static Headers empty() {
            return new Headers("", "", List.of(), List.of(), List.of(),
                    "", "", List.of(), List.of(), "", "", "", "");
        }
    }

    /**
     * Parses a raw RFC 5322 MIME message (plain or base64-encoded) into
     * {@link Headers}. Returns an empty result when the input is blank
     * or unparseable.
     */
    public static Headers parseRawHeaders(String rawMessage) {
        return parseRawMessage(rawMessage).headers();
    }

    /**
     * The decoded bytes of a raw message, the parsed message ({@code null} when the parser cannot
     * read it) and the headers read off it, from one parse that the send path shares between
     * header extraction and the content scan.
     */
    public record Parsed(byte[] bytes, Message message, Headers headers) {}

    public static Parsed parseRawMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return new Parsed(new byte[0], null, Headers.empty());
        }
        byte[] mimeBytes = tryBase64Decode(rawMessage);
        Message message = SesMimeParser.parse(mimeBytes);
        return new Parsed(mimeBytes, message,
                message == null ? Headers.empty() : headersOf(message));
    }

    private static Headers headersOf(Message message) {
        try {
            String subject = message.getSubject() != null ? message.getSubject() : "";
            List<String> from = message.getFrom() != null
                    ? toMailboxAddresses(message.getFrom()) : List.of();
            List<String> to = message.getTo() != null
                    ? toMailboxAddresses(message.getTo().flatten()) : List.of();
            List<String> cc = message.getCc() != null
                    ? toMailboxAddresses(message.getCc().flatten()) : List.of();
            List<String> bcc = message.getBcc() != null
                    ? toMailboxAddresses(message.getBcc().flatten()) : List.of();
            String returnPath = extractAngleAddress(headerValue(message, HEADER_RETURN_PATH));
            String configurationSet = headerValue(message, HEADER_CONFIGURATION_SET);
            List<MessageTag> messageTags = parseMessageTagsHeader(
                    headerValue(message, HEADER_MESSAGE_TAGS));
            Mailbox sender = message.getSender();
            return new Headers(from.isEmpty() ? "" : from.get(0), subject, to, cc, bcc,
                    returnPath == null ? "" : returnPath,
                    configurationSet == null ? "" : configurationSet,
                    messageTags, from,
                    sender == null ? "" : sender.getAddress(),
                    headerValueOrEmpty(message, HEADER_SOURCE_ARN),
                    headerValueOrEmpty(message, HEADER_FROM_ARN),
                    headerValueOrEmpty(message, HEADER_RETURN_PATH_ARN));
        } catch (Exception ignored) {
            // A message whose addresses cannot be read is treated like one without headers.
            return Headers.empty();
        }
    }

    /**
     * Parses an {@code X-SES-MESSAGE-TAGS} header body: a comma-separated list of
     * {@code name=value} pairs. Entries without a name are skipped.
     */
    static List<MessageTag> parseMessageTagsHeader(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<MessageTag> tags = new ArrayList<>();
        for (String pair : value.split(",")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String name = pair.substring(0, separator).trim();
            if (name.isEmpty()) {
                continue;
            }
            tags.add(new MessageTag(name, pair.substring(separator + 1).trim()));
        }
        return List.copyOf(tags);
    }

    /**
     * Returns the flat list of recipients (To + Cc + Bcc) parsed from a raw RFC 5322
     * MIME message. Convenience wrapper around {@link #parseRawHeaders}.
     */
    static List<String> parseRawRecipients(String rawMessage) {
        Headers h = parseRawHeaders(rawMessage);
        List<String> all = new ArrayList<>();
        all.addAll(h.to());
        all.addAll(h.cc());
        all.addAll(h.bcc());
        return all;
    }

    /**
     * The trimmed body of the first {@code name} field on {@code entity}, or {@code null} when the
     * field is absent or blank.
     */
    public static String headerValue(Entity entity, String name) {
        Header header = entity.getHeader();
        if (header == null) {
            return null;
        }
        Field field = header.getField(name);
        if (field == null || field.getBody() == null) {
            return null;
        }
        String value = field.getBody().trim();
        return value.isEmpty() ? null : value;
    }

    private static String headerValueOrEmpty(Entity entity, String name) {
        String value = headerValue(entity, name);
        return value == null ? "" : value;
    }

    /** Whether {@code name} is an {@code X-SES-*} control header, which SES consumes rather than delivers. */
    public static boolean isControlHeader(String name) {
        return name.regionMatches(true, 0, CONTROL_HEADER_PREFIX, 0, CONTROL_HEADER_PREFIX.length());
    }

    /** Unwraps the {@code <addr@example.com>} form used by Return-Path and friends. */
    public static String extractAngleAddress(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 2) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static List<String> toMailboxAddresses(MailboxList list) {
        List<String> addresses = new ArrayList<>();
        for (Mailbox mailbox : list) {
            addresses.add(mailbox.getAddress());
        }
        return addresses;
    }

    /**
     * Decodes the {@code RawMessage.Data} blob, which SDK callers send base64-encoded but which
     * Floci also accepts as plain RFC 5322 text.
     *
     * <p>The alphabet is checked before decoding rather than relying on the decoder to reject
     * plain text: {@link Base64#getMimeDecoder()} skips characters outside the base64 alphabet, so
     * a plain-text message decodes into garbage whenever the remaining alphabet characters happen
     * to form a valid length. A real RFC 5322 message always carries a header separator, which is
     * not in the alphabet, so the check separates the two cases reliably.
     */
    public static byte[] tryBase64Decode(String data) {
        if (looksBase64Encoded(data)) {
            try {
                return Base64.getMimeDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                LOG.debugv("SES raw message looked base64-encoded but did not decode, "
                        + "treating it as plain text");
            }
        }
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean looksBase64Encoded(String data) {
        boolean sawPayload = false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t' || c == '=') {
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/') {
                sawPayload = true;
                continue;
            }
            return false;
        }
        return sawPayload;
    }
}
