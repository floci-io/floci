package io.github.hectorvent.floci.services.ses;

import io.vertx.core.Future;
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

    private SmtpRelay enabledRelay() {
        when(mailClient.sendMail(any(MailMessage.class)))
                .thenReturn(Future.succeededFuture(new MailResult()));
        return new SmtpRelay(mailClient, true);
    }

    @Test
    void relay_whenDisabled_doesNotSend() {
        SmtpRelay relay = new SmtpRelay(mailClient, false);
        assertFalse(relay.isEnabled());

        relay.relay("from@example.com", List.of("to@example.com"),
                null, null, null, "Subject", "text", null, List.of());

        verify(mailClient, never()).sendMail(any(MailMessage.class));
    }

    @Test
    void relay_whenEnabled_sendsMail() {
        SmtpRelay relay = enabledRelay();

        relay.relay("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                List.of("reply@example.com"),
                "Test Subject",
                "plain text",
                "<p>html</p>", List.of());

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

        relay.relay("from@example.com", List.of("to@example.com"),
                null, null, null, "Subject", "text", null, List.of());

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertNull(captor.getValue().getHeaders());
    }

    @Test
    void relay_textOnly_setsTextWithoutHtml() {
        SmtpRelay relay = enabledRelay();

        relay.relay("from@example.com", List.of("to@example.com"),
                null, null, null, "Subject", "only text", null, List.of());

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("only text", captor.getValue().getText());
        assertNull(captor.getValue().getHtml());
    }

    @Test
    void relay_htmlOnly_setsHtmlWithoutText() {
        SmtpRelay relay = enabledRelay();

        relay.relay("from@example.com", List.of("to@example.com"),
                null, null, null, "Subject", null, "<b>html</b>", List.of());

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

        assertDoesNotThrow(() -> relay.relay("from@example.com",
                List.of("to@example.com"), null, null, null, "Subject", "text", null, List.of()));
    }

    // ── Raw relay ──

    @Test
    void relayRaw_whenDisabled_doesNotSend() {
        SmtpRelay relay = new SmtpRelay(mailClient, false);

        relay.relayRaw("from@example.com", List.of("to@example.com"), "raw");

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
        relay.relayRaw("envelope@example.com", List.of("dest@example.com"), rawMime);

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
        relay.relayRaw("from@example.com", List.of("to@example.com"), encoded);

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
        relay.relayRaw("from@example.com", List.of("to@example.com"), rawMime);

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
        relay.relayRaw("envelope@example.com", null, rawMime);

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
        relay.relayRaw("envelope@example.com", List.of("dest@example.com"), rawMime);

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
        relay.relayRaw("from@example.com", List.of("to@example.com"), rawMime);

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
        relay.relayRaw("from@example.com", List.of("to@example.com"), rawMime);

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailClient).sendMail(captor.capture());
        assertEquals("real body", captor.getValue().getText().trim());
    }

    @Test
    void relayRaw_mailClientThrows_doesNotPropagate() {
        SmtpRelay relay = new SmtpRelay(mailClient, true);
        when(mailClient.sendMail(any(MailMessage.class)))
                .thenReturn(Future.failedFuture(new RuntimeException("SMTP timeout")));

        assertDoesNotThrow(() -> relay.relayRaw("from@example.com",
                List.of("to@example.com"), "Subject: X\r\n\r\nBody"));
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
}
