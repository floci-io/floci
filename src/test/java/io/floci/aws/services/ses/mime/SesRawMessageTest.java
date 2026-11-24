package io.floci.aws.services.ses.mime;

import io.floci.aws.services.ses.model.MessageTag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesRawMessageTest {

    @Test
    void parseRawRecipients_extractsToCcBcc() {
        String raw = "From: sender@example.com\r\n"
                + "To: to1@example.com, to2@example.com\r\n"
                + "Cc: cc@example.com\r\n"
                + "Bcc: bcc@example.com\r\n"
                + "Subject: test\r\n\r\nbody";
        List<String> recipients = SesRawMessage.parseRawRecipients(raw);
        assertEquals(List.of("to1@example.com", "to2@example.com", "cc@example.com", "bcc@example.com"),
                recipients);
    }

    @Test
    void parseRawRecipients_acceptsBase64Encoded() {
        String raw = "From: a@example.com\r\nTo: only@example.com\r\nSubject: x\r\n\r\nbody";
        String b64 = Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("only@example.com"), SesRawMessage.parseRawRecipients(b64));
    }

    @Test
    void tryBase64Decode_plainTextMime_isNotTreatedAsBase64() {
        // The MIME decoder skips characters outside the base64 alphabet, so a plain-text message
        // whose remaining alphabet characters happen to form a valid length would otherwise decode
        // into garbage.
        String raw = "From: s@example.com\r\nMessage-ID: <caller@example.com>\r\nSubject: x\r\n\r\nbody";
        assertEquals(raw, new String(SesRawMessage.tryBase64Decode(raw),
                StandardCharsets.UTF_8));
    }

    @Test
    void tryBase64Decode_base64Payload_isDecoded() {
        String raw = "From: s@example.com\r\nSubject: x\r\n\r\nbody";
        String encoded = Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
        assertEquals(raw, new String(SesRawMessage.tryBase64Decode(encoded),
                StandardCharsets.UTF_8));
    }

    @Test
    void parseRawRecipients_blankOrUnparseable_returnsEmpty() {
        assertTrue(SesRawMessage.parseRawRecipients(null).isEmpty());
        assertTrue(SesRawMessage.parseRawRecipients("").isEmpty());
        assertTrue(SesRawMessage.parseRawRecipients("   ").isEmpty());
    }

    @Test
    void parseRawRecipients_noRecipientHeaders_returnsEmpty() {
        String raw = "From: a@example.com\r\nSubject: x\r\n\r\nbody";
        assertTrue(SesRawMessage.parseRawRecipients(raw).isEmpty());
    }

    @Test
    void parseRawHeaders_extractsSubjectAndStructuredRecipients() {
        String raw = "From: sender@example.com\r\n"
                + "To: to1@example.com, to2@example.com\r\n"
                + "Cc: cc@example.com\r\n"
                + "Bcc: bcc@example.com\r\n"
                + "Subject: Hello world\r\n\r\nbody";
        String b64 = Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(b64);
        assertEquals("Hello world", h.subject());
        assertEquals(List.of("to1@example.com", "to2@example.com"), h.to());
        assertEquals(List.of("cc@example.com"), h.cc());
        assertEquals(List.of("bcc@example.com"), h.bcc());
    }

    @Test
    void parseRawHeaders_nestedBeyondTheParser_returnsEmptyRecord() {
        // mime4j nests a reader per forwarded message and overflows the stack at this depth; the
        // header pre-parse must answer like any other unparseable message, not kill the request.
        String raw = "Content-Type: message/rfc822\r\n\r\n".repeat(100_000) + "Subject: deep\r\n\r\nx\r\n";

        SesRawMessage.Parsed parsed = SesRawMessage.parseRawMessage(raw);
        assertNull(parsed.message());
        assertEquals("", parsed.headers().subject());
        assertTrue(parsed.headers().to().isEmpty());
    }

    @Test
    void parseRawHeaders_blank_returnsEmptyRecord() {
        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(null);
        assertEquals("", h.subject());
        assertTrue(h.to().isEmpty());
        assertTrue(h.cc().isEmpty());
        assertTrue(h.bcc().isEmpty());
    }

    @Test
    void parseRawHeaders_extractsReturnPathAndSesControlHeaders() {
        String raw = "From: sender@example.com\r\n"
                + "To: to@example.com\r\n"
                + "Return-Path: <bounces@example.com>\r\n"
                + "X-SES-CONFIGURATION-SET: my-config-set\r\n"
                + "X-SES-MESSAGE-TAGS: campaign=spring, tier=gold\r\n"
                + "Subject: x\r\n\r\nbody";

        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(raw);

        assertEquals("bounces@example.com", h.returnPath());
        assertEquals("my-config-set", h.configurationSet());
        assertEquals(List.of(new MessageTag("campaign", "spring"), new MessageTag("tier", "gold")),
                h.messageTags());
    }

    @Test
    void parseRawHeaders_withoutSesHeaders_returnsBlanksAndNoTags() {
        String raw = "From: sender@example.com\r\nTo: to@example.com\r\nSubject: x\r\n\r\nbody";

        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(raw);

        assertEquals("", h.returnPath());
        assertEquals("", h.configurationSet());
        assertTrue(h.messageTags().isEmpty());
    }

    @Test
    void parseRawHeaders_extractsSenderAndTheFirstSendingAuthorizationHeaders() {
        String raw = "From: sender@example.com\r\n"
                + "Sender: Assistant <assistant@example.com>\r\n"
                + "To: to@example.com\r\n"
                + "X-SES-SOURCE-ARN: arn:aws:ses:us-east-1:111111111111:identity/example.com\r\n"
                + "X-SES-SOURCE-ARN: arn:aws:ses:us-east-1:222222222222:identity/example.com\r\n"
                + "X-SES-FROM-ARN: arn:aws:ses:us-east-1:333333333333:identity/example.com\r\n"
                + "X-SES-RETURN-PATH-ARN: arn:aws:ses:us-east-1:444444444444:identity/example.com\r\n"
                + "Subject: x\r\n\r\nbody";

        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(raw);

        assertEquals("assistant@example.com", h.sender());
        assertEquals("arn:aws:ses:us-east-1:111111111111:identity/example.com", h.sourceArn());
        assertEquals("arn:aws:ses:us-east-1:333333333333:identity/example.com", h.fromArn());
        assertEquals("arn:aws:ses:us-east-1:444444444444:identity/example.com", h.returnPathArn());
    }

    @Test
    void parseRawHeaders_withoutSenderOrSendingAuthorizationHeaders_returnsBlanks() {
        String raw = "From: sender@example.com\r\nTo: to@example.com\r\nSubject: x\r\n\r\nbody";

        SesRawMessage.Headers h = SesRawMessage.parseRawHeaders(raw);

        assertEquals("", h.sender());
        assertEquals("", h.sourceArn());
        assertEquals("", h.fromArn());
        assertEquals("", h.returnPathArn());
    }

    @Test
    void parseMessageTagsHeader_skipsEntriesWithoutAName() {
        assertEquals(List.of(new MessageTag("ok", "1")),
                SesRawMessage.parseMessageTagsHeader("ok=1, =2, novalueseparator"));
        assertTrue(SesRawMessage.parseMessageTagsHeader(null).isEmpty());
        assertTrue(SesRawMessage.parseMessageTagsHeader("   ").isEmpty());
    }

    @Test
    void parseRawRecipients_stripsDisplayNames() {
        // The envelope, suppression checks and published events all work with bare addresses.
        String raw = "From: Alice <alice@example.com>\r\n"
                + "To: Bob Recipient <bob@example.com>\r\n"
                + "Subject: x\r\n\r\nbody";
        assertEquals(List.of("bob@example.com"), SesRawMessage.parseRawRecipients(raw));
        assertEquals("alice@example.com", SesRawMessage.parseRawHeaders(raw).from());
    }

    @Test
    void isControlHeader_matchesTheXSesPrefixInAnyCase() {
        assertTrue(SesRawMessage.isControlHeader("X-SES-CONFIGURATION-SET"));
        assertTrue(SesRawMessage.isControlHeader("x-ses-message-tags"));
        assertFalse(SesRawMessage.isControlHeader("X-SESSION"));
        assertFalse(SesRawMessage.isControlHeader("X-Mailer"));
    }
}
