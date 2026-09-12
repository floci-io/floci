package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.vertx.core.Future;
import io.vertx.ext.mail.MailAttachment;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.MailResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmtpRelayTest {

    @Mock MailClient mailClient;

    private static SmtpRelay.RelayMessage simple(String text) {
        return SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyText(text)
                .headers(List.of())
                .build();
    }

    private static SmtpRelay.RawRelayMessage raw(String from, List<String> destinations, String rawMessage) {
        return new SmtpRelay.RawRelayMessage(from, null, destinations, rawMessage, null);
    }

    private SmtpRelay enabledRelay() {
        when(mailClient.sendMail(any(MailMessage.class)))
                .thenReturn(Future.succeededFuture(new MailResult()));
        return new SmtpRelay(mailClient, true);
    }

    @Test
    void relay_whenDisabled_doesNotSend() {
        SmtpRelay relay = new SmtpRelay(mailClient, false);
        assertFalse(relay.isEnabled());

        relay.relay(simple("text"));

        verify(mailClient, never()).sendMail(any(MailMessage.class));
    }

    @Test
    void relay_whenEnabled_sendsMail() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .cc(List.of("cc@example.com"))
                .bcc(List.of("bcc@example.com"))
                .replyTo(List.of("reply@example.com"))
                .subject("Test Subject")
                .bodyText("plain text")
                .bodyHtml("<p>html</p>")
                .headers(List.of())
                .build());

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());

        MailMessage sent = captor.getValue();
        assertEquals("from@example.com", sent.getFrom());
        assertEquals(List.of("to@example.com"), sent.getTo());
        assertEquals(List.of("cc@example.com"), sent.getCc());
        assertEquals(List.of("bcc@example.com"), sent.getBcc());
        assertEquals("Test Subject", sent.getSubject());
        assertEquals("plain text", sent.getText());
        assertEquals("<p>html</p>", sent.getHtml());
        assertEquals("reply@example.com", sent.getHeaders().get("Reply-To"));
    }

    @Test
    void relay_noReplyTo_omitsHeader() {
        SmtpRelay relay = enabledRelay();

        relay.relay(simple("text"));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertNull(captor.getValue().getHeaders());
    }

    @Test
    void relay_dropsHeadersWithCrlfOrBlankName_preventingInjection() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyText("text")
                .headers(List.of(
                        new MessageHeader("X-Safe", "ok"),
                        new MessageHeader("X-Evil", "value\r\nBcc: attacker@evil.com"),
                        new MessageHeader("Injected\r\nBcc", "x"),
                        new MessageHeader("", "no-name")))
                .build());

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        MailMessage sent = captor.getValue();

        assertEquals("ok", sent.getHeaders().get("X-Safe"));
        // The CR/LF-bearing and blank-name headers are dropped entirely, so no smuggled header
        // (e.g. Bcc) reaches the relayed message and no header name/value carries a line break.
        assertFalse(sent.getHeaders().contains("X-Evil"));
        assertNull(sent.getHeaders().get("Bcc"));
        assertTrue(sent.getHeaders().names().stream()
                .noneMatch(n -> n.isBlank() || n.indexOf('\r') >= 0 || n.indexOf('\n') >= 0));
    }

    @Test
    void relay_textOnly_setsTextWithoutHtml() {
        SmtpRelay relay = enabledRelay();

        relay.relay(simple("only text"));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("only text", captor.getValue().getText());
        assertNull(captor.getValue().getHtml());
    }

    @Test
    void relay_htmlOnly_setsHtmlWithoutText() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyHtml("<b>html</b>")
                .build());

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertNull(captor.getValue().getText());
        assertEquals("<b>html</b>", captor.getValue().getHtml());
    }

    @Test
    void relay_mailClientThrows_doesNotPropagate() {
        SmtpRelay relay = new SmtpRelay(mailClient, true);
        when(mailClient.sendMail(any(MailMessage.class)))
                .thenReturn(Future.failedFuture(new RuntimeException("SMTP refused")));

        assertDoesNotThrow(() -> relay.relay(simple("text")));
    }

    // ── Raw relay ──

    @Test
    void relayRaw_whenDisabled_doesNotSend() {
        SmtpRelay relay = new SmtpRelay(mailClient, false);

        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), "raw"));

        verify(mailClient, never()).sendMail(any(MailMessage.class));
    }

    @Test
    void relayRaw_parsesHeadersAndBody() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: sender@example.com\r\n"
                + "To: to@example.com\r\n"
                + "Subject: Raw Test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Raw body content";
        relay.relayRaw(raw("envelope@example.com", List.of("dest@example.com"), rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());

        MailMessage sent = captor.getValue();
        assertEquals("sender@example.com", sent.getFrom());
        assertEquals(List.of("dest@example.com"), sent.getTo());
        assertEquals("Raw Test", sent.getSubject());
        assertEquals("Raw body content", sent.getText());
    }

    @Test
    void relayRaw_base64Encoded_decodesFirst() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\nTo: t@example.com\r\nSubject: B64\r\n\r\nDecoded body";
        String encoded = java.util.Base64.getEncoder().encodeToString(
                rawMime.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), encoded));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("B64", captor.getValue().getSubject());
        assertEquals("Decoded body", captor.getValue().getText());
    }

    @Test
    void relayRaw_htmlContentType_setsHtml() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\nTo: t@example.com\r\nSubject: HTML\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n<h1>Hello</h1>";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("<h1>Hello</h1>", captor.getValue().getHtml());
        assertNull(captor.getValue().getText());
    }

    @Test
    void relayRaw_destinationsEmpty_extractsBccFromHeaders() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Cc: c@example.com\r\n"
                + "Bcc: b@example.com\r\n"
                + "Subject: BccHeader\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "body";
        relay.relayRaw(raw("envelope@example.com", null, rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        MailMessage sent = captor.getValue();
        assertEquals(List.of("t@example.com"), sent.getTo());
        assertEquals(List.of("c@example.com"), sent.getCc());
        assertEquals(List.of("b@example.com"), sent.getBcc());
    }

    @Test
    void relayRaw_fallsBackToEnvelopeAddresses() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "Subject: NoAddr\r\n\r\nBody";
        relay.relayRaw(raw("envelope@example.com", List.of("dest@example.com"), rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("envelope@example.com", captor.getValue().getFrom());
        assertEquals(List.of("dest@example.com"), captor.getValue().getTo());
    }

    @Test
    void relayRaw_nestedMultipart_extractsTextAndHtml() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: Nested\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: multipart/alternative; boundary=\"inner\"\r\n"
                + "\r\n"
                + "--inner\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "plain text\r\n"
                + "--inner\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<p>html</p>\r\n"
                + "--inner--\r\n"
                + "--outer--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("plain text", captor.getValue().getText().trim());
        assertEquals("<p>html</p>", captor.getValue().getHtml().trim());
    }

    @Test
    void relayRaw_multipartMixedWithTextAttachment_preservesBody() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: Mixed\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "real body\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Disposition: attachment; filename=\"notes.txt\"\r\n"
                + "\r\n"
                + "attachment content that must not clobber body\r\n"
                + "--outer--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("real body", captor.getValue().getText().trim());
    }

    @Test
    void relayRaw_mailClientThrows_doesNotPropagate() {
        SmtpRelay relay = new SmtpRelay(mailClient, true);
        when(mailClient.sendMail(any(MailMessage.class)))
                .thenReturn(Future.failedFuture(new RuntimeException("SMTP timeout")));

        assertDoesNotThrow(() -> relay.relayRaw(raw("from@example.com",
                List.of("to@example.com"), "Subject: X\r\n\r\nBody")));
    }

    @Test
    void parseRawRecipients_extractsToCcBcc() {
        String raw = "From: sender@example.com\r\n"
                + "To: to1@example.com, to2@example.com\r\n"
                + "Cc: cc@example.com\r\n"
                + "Bcc: bcc@example.com\r\n"
                + "Subject: test\r\n\r\nbody";
        List<String> recipients = SmtpRelay.parseRawRecipients(raw);
        assertEquals(List.of("to1@example.com", "to2@example.com", "cc@example.com", "bcc@example.com"),
                recipients);
    }

    @Test
    void parseRawRecipients_acceptsBase64Encoded() {
        String raw = "From: a@example.com\r\nTo: only@example.com\r\nSubject: x\r\n\r\nbody";
        String b64 = java.util.Base64.getEncoder().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(List.of("only@example.com"), SmtpRelay.parseRawRecipients(b64));
    }

    @Test
    void tryBase64Decode_plainTextMime_isNotTreatedAsBase64() {
        // The MIME decoder skips characters outside the base64 alphabet, so a plain-text message
        // whose remaining alphabet characters happen to form a valid length would otherwise decode
        // into garbage.
        String raw = "From: s@example.com\r\nMessage-ID: <caller@example.com>\r\nSubject: x\r\n\r\nbody";
        assertEquals(raw, new String(SmtpRelay.tryBase64Decode(raw),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void tryBase64Decode_base64Payload_isDecoded() {
        String raw = "From: s@example.com\r\nSubject: x\r\n\r\nbody";
        String encoded = java.util.Base64.getEncoder().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(raw, new String(SmtpRelay.tryBase64Decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void parseRawRecipients_blankOrUnparseable_returnsEmpty() {
        assertTrue(SmtpRelay.parseRawRecipients(null).isEmpty());
        assertTrue(SmtpRelay.parseRawRecipients("").isEmpty());
        assertTrue(SmtpRelay.parseRawRecipients("   ").isEmpty());
    }

    @Test
    void parseRawRecipients_noRecipientHeaders_returnsEmpty() {
        String raw = "From: a@example.com\r\nSubject: x\r\n\r\nbody";
        assertTrue(SmtpRelay.parseRawRecipients(raw).isEmpty());
    }

    @Test
    void parseRawHeaders_extractsSubjectAndStructuredRecipients() {
        String raw = "From: sender@example.com\r\n"
                + "To: to1@example.com, to2@example.com\r\n"
                + "Cc: cc@example.com\r\n"
                + "Bcc: bcc@example.com\r\n"
                + "Subject: Hello world\r\n\r\nbody";
        String b64 = java.util.Base64.getEncoder().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SmtpRelay.RawMessageHeaders h = SmtpRelay.parseRawHeaders(b64);
        assertEquals("Hello world", h.subject());
        assertEquals(List.of("to1@example.com", "to2@example.com"), h.to());
        assertEquals(List.of("cc@example.com"), h.cc());
        assertEquals(List.of("bcc@example.com"), h.bcc());
    }

    @Test
    void parseRawHeaders_blank_returnsEmptyRecord() {
        SmtpRelay.RawMessageHeaders h = SmtpRelay.parseRawHeaders(null);
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

        SmtpRelay.RawMessageHeaders h = SmtpRelay.parseRawHeaders(raw);

        assertEquals("bounces@example.com", h.returnPath());
        assertEquals("my-config-set", h.configurationSet());
        assertEquals(List.of(new MessageTag("campaign", "spring"), new MessageTag("tier", "gold")),
                h.messageTags());
    }

    @Test
    void parseRawHeaders_withoutSesHeaders_returnsBlanksAndNoTags() {
        String raw = "From: sender@example.com\r\nTo: to@example.com\r\nSubject: x\r\n\r\nbody";

        SmtpRelay.RawMessageHeaders h = SmtpRelay.parseRawHeaders(raw);

        assertEquals("", h.returnPath());
        assertEquals("", h.configurationSet());
        assertTrue(h.messageTags().isEmpty());
    }

    @Test
    void parseMessageTagsHeader_skipsEntriesWithoutAName() {
        assertEquals(List.of(new MessageTag("ok", "1")),
                SmtpRelay.parseMessageTagsHeader("ok=1, =2, novalueseparator"));
        assertTrue(SmtpRelay.parseMessageTagsHeader(null).isEmpty());
        assertTrue(SmtpRelay.parseMessageTagsHeader("   ").isEmpty());
    }

    @Test
    void formatMessageId_usesTheReturnedMessageIdAsTheLocalPart() {
        assertEquals("<abc-123@email.amazonses.com>", SmtpRelay.formatMessageId("abc-123"));
        assertNull(SmtpRelay.formatMessageId(null));
        assertNull(SmtpRelay.formatMessageId("  "));
    }

    // ── Envelope sender and Message-ID ──

    @Test
    void relay_returnPath_becomesTheEnvelopeSender() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .returnPath("bounces@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyText("text")
                .build());

        assertEquals("bounces@example.com", captureSent().getBounceAddress());
    }

    @Test
    void relay_noReturnPath_envelopeSenderFallsBackToFrom() {
        SmtpRelay relay = enabledRelay();

        relay.relay(simple("text"));

        assertEquals("from@example.com", captureSent().getBounceAddress());
    }

    @Test
    void relay_stampsMessageIdInTheAwsFormat() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyText("text")
                .messageId("msg-1")
                .build());

        assertEquals("<msg-1@email.amazonses.com>",
                captureSent().getHeaders().get("Message-ID"));
    }

    @Test
    void relay_callerSuppliedMessageIdHeader_isOverridden() {
        SmtpRelay relay = enabledRelay();

        relay.relay(SmtpRelay.RelayMessage.builder("from@example.com")
                .to(List.of("to@example.com"))
                .subject("Subject")
                .bodyText("text")
                .headers(List.of(new MessageHeader("Message-ID", "<caller@example.com>")))
                .messageId("msg-1")
                .build());

        // AWS overrides a caller-supplied Message-ID with the id it assigned.
        assertEquals(List.of("<msg-1@email.amazonses.com>"),
                captureSent().getHeaders().getAll("Message-ID"));
    }

    @Test
    void relayRaw_returnPath_becomesTheEnvelopeSender() {
        SmtpRelay relay = enabledRelay();

        relay.relayRaw(new SmtpRelay.RawRelayMessage("from@example.com", "bounces@example.com",
                List.of("to@example.com"), "From: s@example.com\r\nSubject: x\r\n\r\nbody",
                "msg-1"));

        assertEquals("bounces@example.com", captureSent().getBounceAddress());
    }

    @Test
    void relayRaw_stampsMessageIdWhenTheMessageHasNone() {
        SmtpRelay relay = enabledRelay();

        relay.relayRaw(new SmtpRelay.RawRelayMessage("from@example.com", null,
                List.of("to@example.com"), "From: s@example.com\r\nSubject: x\r\n\r\nbody",
                "msg-1"));

        assertEquals("<msg-1@email.amazonses.com>",
                captureSent().getHeaders().get("Message-ID"));
    }

    @Test
    void relayRaw_overridesACallerSuppliedMessageId() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "Message-ID: <caller-supplied@example.com>\r\n"
                + "Subject: x\r\n\r\nbody";
        relay.relayRaw(new SmtpRelay.RawRelayMessage("from@example.com", null,
                List.of("to@example.com"), rawMime, "msg-1"));

        // AWS overrides a caller-supplied Message-ID with the id it assigned.
        assertEquals(List.of("<msg-1@email.amazonses.com>"),
                captureSent().getHeaders().getAll("Message-ID"));
    }

    @Test
    void relayRaw_dropsACallerSuppliedDate() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "Date: Mon, 08 Oct 2018 14:05:45 +0000\r\n"
                + "Subject: x\r\n\r\nbody";
        relay.relayRaw(new SmtpRelay.RawRelayMessage("from@example.com", null,
                List.of("to@example.com"), rawMime, "msg-1"));

        // AWS replaces the caller's Date with the time it accepted the message, so the stale value
        // must not be carried onto the relayed message; the encoder generates the send-time Date.
        MailMessage sent = captureSent();
        assertNull(sent.getHeaders().get("Date"));
        assertEquals("<msg-1@email.amazonses.com>", sent.getHeaders().get("Message-ID"));
    }

    // ── Raw relay fidelity: headers and attachments ──

    @Test
    void relayRaw_copiesCustomHeadersButNotStructuralOrSesOnes() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: Headers\r\n"
                + "Reply-To: reply@example.com\r\n"
                + "X-Custom: keep-me\r\n"
                + "X-SES-CONFIGURATION-SET: cs\r\n"
                + "X-SES-MESSAGE-TAGS: a=b\r\n"
                + "Return-Path: <bounces@example.com>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "body";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        MailMessage sent = captureSent();
        assertEquals("keep-me", sent.getHeaders().get("X-Custom"));
        assertEquals("reply@example.com", sent.getHeaders().get("Reply-To"));
        // AWS consumes the X-SES-* control headers rather than forwarding them, and the MIME
        // structural headers are regenerated for the re-built message.
        assertFalse(sent.getHeaders().contains("X-SES-CONFIGURATION-SET"));
        assertFalse(sent.getHeaders().contains("X-SES-MESSAGE-TAGS"));
        assertFalse(sent.getHeaders().contains("Content-Type"));
        assertFalse(sent.getHeaders().contains("MIME-Version"));
        assertFalse(sent.getHeaders().contains("Return-Path"));
        assertFalse(sent.getHeaders().contains("Date"));
        assertFalse(sent.getHeaders().contains("Subject"));
        assertFalse(sent.getHeaders().contains("From"));
    }

    @Test
    void relayRaw_keepsDisplayNamesOnAddresses() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: Alice Sender <alice@example.com>\r\n"
                + "To: Bob Recipient <bob@example.com>\r\n"
                + "Cc: Carol <carol@example.com>\r\n"
                + "Subject: Names\r\n\r\nbody";
        relay.relayRaw(raw("envelope@example.com", null, rawMime));

        MailMessage sent = captureSent();
        assertEquals("Alice Sender <alice@example.com>", sent.getFrom());
        assertEquals(List.of("Bob Recipient <bob@example.com>"), sent.getTo());
        assertEquals(List.of("Carol <carol@example.com>"), sent.getCc());
    }

    @Test
    void parseRawRecipients_stripsDisplayNames() {
        // The envelope, suppression checks and published events all work with bare addresses.
        String raw = "From: Alice <alice@example.com>\r\n"
                + "To: Bob Recipient <bob@example.com>\r\n"
                + "Subject: x\r\n\r\nbody";
        assertEquals(List.of("bob@example.com"), SmtpRelay.parseRawRecipients(raw));
        assertEquals("alice@example.com", SmtpRelay.parseRawHeaders(raw).from());
    }

    @Test
    void relayRaw_preservesAttachments() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: WithAttachment\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "the body\r\n"
                + "--outer\r\n"
                + "Content-Type: application/pdf\r\n"
                + "Content-Disposition: attachment; filename=\"report.pdf\"\r\n"
                + "Content-Description: Quarterly report\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + java.util.Base64.getEncoder().encodeToString("PDF-BYTES".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)) + "\r\n"
                + "--outer--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        MailMessage sent = captureSent();
        assertEquals("the body", sent.getText().trim());
        assertNotNull(sent.getAttachment());
        assertEquals(1, sent.getAttachment().size());
        MailAttachment attachment = sent.getAttachment().get(0);
        assertEquals("report.pdf", attachment.getName());
        assertEquals("application/pdf", attachment.getContentType());
        assertEquals("attachment", attachment.getDisposition());
        assertEquals("Quarterly report", attachment.getDescription());
        assertEquals("PDF-BYTES", attachment.getData().toString());
    }

    @Test
    void relayRaw_contentIdPart_becomesAnInlineAttachment() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: Inline\r\n"
                + "Content-Type: multipart/related; boundary=\"rel\"\r\n"
                + "\r\n"
                + "--rel\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<img src=\"cid:logo\">\r\n"
                + "--rel\r\n"
                + "Content-Type: image/png\r\n"
                + "Content-ID: <logo>\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + java.util.Base64.getEncoder().encodeToString("PNG".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)) + "\r\n"
                + "--rel--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        MailMessage sent = captureSent();
        assertNull(sent.getAttachment());
        assertNotNull(sent.getInlineAttachment());
        assertEquals(1, sent.getInlineAttachment().size());
        MailAttachment inline = sent.getInlineAttachment().get(0);
        assertEquals("<logo>", inline.getContentId());
        assertEquals("image/png", inline.getContentType());
        assertEquals("PNG", inline.getData().toString());
    }

    @Test
    void relayRaw_inlineAttachmentWithoutHtmlBody_isRelayedAsARegularAttachment() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: InlineNoHtml\r\n"
                + "Content-Type: multipart/related; boundary=\"rel\"\r\n"
                + "\r\n"
                + "--rel\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "plain only\r\n"
                + "--rel\r\n"
                + "Content-Type: image/png\r\n"
                + "Content-ID: <logo>\r\n"
                + "\r\n"
                + "PNG\r\n"
                + "--rel--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        MailMessage sent = captureSent();
        assertNull(sent.getInlineAttachment());
        assertNotNull(sent.getAttachment());
        assertEquals(1, sent.getAttachment().size());
    }

    @Test
    void relayRaw_textAttachmentDoesNotClobberTheBody() {
        SmtpRelay relay = enabledRelay();

        String rawMime = "From: s@example.com\r\n"
                + "To: t@example.com\r\n"
                + "Subject: Mixed\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "real body\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Disposition: attachment; filename=\"notes.txt\"\r\n"
                + "\r\n"
                + "attachment content\r\n"
                + "--outer--";
        relay.relayRaw(raw("from@example.com", List.of("to@example.com"), rawMime));

        MailMessage sent = captureSent();
        assertEquals("real body", sent.getText().trim());
        assertEquals(1, sent.getAttachment().size());
        assertEquals("notes.txt", sent.getAttachment().get(0).getName());
    }

    private MailMessage captureSent() {
        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        return captor.getValue();
    }
}
