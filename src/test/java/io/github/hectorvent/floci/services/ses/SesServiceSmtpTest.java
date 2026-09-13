package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SesServiceSmtpTest {

    @Mock SmtpRelay smtpRelay;

    private SesService service;
    private InMemoryStorage<String, SentEmail> emailStore;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create().smtpRelay(smtpRelay);
        emailStore = builder.emailStore();
        service = builder.build();
    }

    private SentEmail storedEmail(String messageId) {
        return service.getEmails().stream()
                .filter(e -> messageId.equals(e.getMessageId()))
                .findFirst()
                .orElseThrow();
    }

    private SmtpRelay.RelayMessage capturedRelay() {
        ArgumentCaptor<SmtpRelay.RelayMessage> captor =
                ArgumentCaptor.forClass(SmtpRelay.RelayMessage.class);
        verify(smtpRelay).relay(captor.capture());
        return captor.getValue();
    }

    private SmtpRelay.RawRelayMessage capturedRawRelay() {
        ArgumentCaptor<SmtpRelay.RawRelayMessage> captor =
                ArgumentCaptor.forClass(SmtpRelay.RawRelayMessage.class);
        verify(smtpRelay).relayRaw(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendEmail_callsRelayWithAllFields() {
        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                List.of("reply@example.com"),
                null,
                "Subject", "text body", "<p>html</p>", null, List.of(), List.of(), null, "us-east-1");

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals("from@example.com", relayed.from());
        assertEquals(List.of("to@example.com"), relayed.to());
        assertEquals(List.of("cc@example.com"), relayed.cc());
        assertEquals(List.of("bcc@example.com"), relayed.bcc());
        assertEquals(List.of("reply@example.com"), relayed.replyTo());
        assertEquals("Subject", relayed.subject());
        assertEquals("text body", relayed.bodyText());
        assertEquals("<p>html</p>", relayed.bodyHtml());
        assertEquals(List.of(), relayed.headers());
        assertEquals(messageId, relayed.messageId());
    }

    @Test
    void sendEmail_storesAndRelays() {
        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null, null,
                "Subject", "text", null, null, List.of(), List.of(), null, "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendEmail_noReturnPath_fallsBackToSource() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null, null,
                "Subject", "text", null, null, List.of(), List.of(), null, "us-east-1");

        assertEquals("from@example.com", capturedRelay().returnPath());
    }

    @Test
    void sendEmail_explicitReturnPath_isRelayedAndStored() {
        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null, "bounces@example.com",
                "Subject", "text", null, null, List.of(), List.of(), null, "us-east-1");

        assertEquals("bounces@example.com", capturedRelay().returnPath());
        assertEquals("bounces@example.com", storedEmail(messageId).getReturnPath());
    }

    @Test
    void sendRawEmail_callsRelayRaw() {
        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", null, null, List.of(), null, "us-east-1");

        SmtpRelay.RawRelayMessage relayed = capturedRawRelay();
        assertEquals("from@example.com", relayed.from());
        assertEquals(List.of("to@example.com"), relayed.destinations());
        assertEquals("raw MIME", relayed.rawMessage());
        assertEquals(messageId, relayed.messageId());
    }

    @Test
    void sendRawEmail_returnPathHeader_winsOverRequestField() {
        String raw = "From: from@example.com\r\n"
                + "To: to@example.com\r\n"
                + "Return-Path: <mime-bounces@example.com>\r\n"
                + "Subject: x\r\n\r\nbody";

        String messageId = service.sendRawEmail("from@example.com", List.of("to@example.com"), raw,
                "request-bounces@example.com", null, List.of(), null, "us-east-1");

        assertEquals("mime-bounces@example.com", capturedRawRelay().returnPath());
        assertEquals("mime-bounces@example.com", storedEmail(messageId).getReturnPath());
    }

    @Test
    void sendRawEmail_noReturnPathHeader_usesRequestField() {
        String raw = "From: from@example.com\r\nTo: to@example.com\r\nSubject: x\r\n\r\nbody";

        service.sendRawEmail("from@example.com", List.of("to@example.com"), raw,
                "request-bounces@example.com", null, List.of(), null, "us-east-1");

        assertEquals("request-bounces@example.com", capturedRawRelay().returnPath());
    }

    @Test
    void sendRawEmail_storesAndRelays() {
        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw", null, null, List.of(), null, "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relayRaw(any(SmtpRelay.RawRelayMessage.class));
    }

    @Test
    void sendEmail_relayReceivesCorrectFieldsWithNulls() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"),
                null, null, null, null,
                "Subject", null, "<p>html only</p>", null, List.of(), List.of(), null, "us-east-1");

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals(List.of("to@example.com"), relayed.to());
        assertNull(relayed.cc());
        assertNull(relayed.bcc());
        assertNull(relayed.replyTo());
        assertNull(relayed.bodyText());
        assertEquals("<p>html only</p>", relayed.bodyHtml());
    }

    @Test
    void sendEmail_allRecipientsSuppressed_skipsRelayButStillStores() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.putSuppressedDestination("us-east-1", "cc@example.com", "COMPLAINT");

        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                null, null, null,
                "Subject", "text body", null, null, List.of(), List.of(), null, "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty(),
                "stored SentEmail should still record the original recipient list");
        verify(smtpRelay, never()).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendEmail_partialSuppression_relayCalledWithFilteredRecipients() {
        // Only suppress one of the To recipients; the other should still reach the relay.
        service.putSuppressedDestination("us-east-1", "suppressed@example.com", "BOUNCE");

        service.sendEmail("from@example.com",
                List.of("to@example.com", "suppressed@example.com"),
                List.of("cc-keep@example.com"),
                null, null, null,
                "Subject", "text body", null, null, List.of(), List.of(), null, "us-east-1");

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals(List.of("to@example.com"), relayed.to());
        assertEquals(List.of("cc-keep@example.com"), relayed.cc());
    }

    @Test
    void sendRawEmail_allRecipientsSuppressed_skipsRelayRawButStillStores() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");

        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", null, null, List.of(), null, "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay, never()).relayRaw(any(SmtpRelay.RawRelayMessage.class));
    }

    @Test
    void sendRawEmail_partialSuppression_relayRawCalledWithFilteredRecipients() {
        service.putSuppressedDestination("us-east-1", "suppressed@example.com", "COMPLAINT");

        service.sendRawEmail("from@example.com",
                List.of("to@example.com", "suppressed@example.com"),
                "raw MIME", null, null, List.of(), null, "us-east-1");

        assertEquals(List.of("to@example.com"), capturedRawRelay().destinations());
    }

    // ─────────── Per-CS SuppressionOptions override at send time ───────────

    @Test
    void sendEmail_csOverridesAccountToEmptyList_suppressionListIsIgnored() {
        // Account defaults to [BOUNCE, COMPLAINT] suppression. The CS explicitly
        // overrides to an empty list, which is the AWS V2 contract for "disable
        // suppression filtering for this configuration set". A recipient on the
        // suppression list with reason=BOUNCE should still reach the SMTP relay.
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-no-suppression"), "us-east-1");
        service.putConfigurationSetSuppressionOptions("cs-no-suppression", List.of(), "us-east-1");

        service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null, null,
                "Subject", "text body", null, "cs-no-suppression", List.of(), List.of(), null, "us-east-1");

        assertEquals(List.of("to@example.com"), capturedRelay().to());
    }

    @Test
    void sendEmail_csOverridesAccountToBounceOnly_complaintSuppressedAddressStillRelayed() {
        // Account-level reasons include both BOUNCE and COMPLAINT by default.
        // The CS narrows the effective reasons to [BOUNCE] only — so a recipient
        // suppressed for COMPLAINT is NOT filtered when sending through this CS.
        service.putSuppressedDestination("us-east-1", "complainer@example.com", "COMPLAINT");
        service.createConfigurationSet(new ConfigurationSet("cs-bounce-only"), "us-east-1");
        service.putConfigurationSetSuppressionOptions("cs-bounce-only", List.of("BOUNCE"), "us-east-1");

        service.sendEmail("from@example.com",
                List.of("complainer@example.com"), null, null, null, null,
                "Subject", "text body", null, "cs-bounce-only", List.of(), List.of(), null, "us-east-1");

        assertEquals(List.of("complainer@example.com"), capturedRelay().to());
    }

    @Test
    void sendEmail_csWithoutOverride_fallsBackToAccountLevelSuppression() {
        // A configuration set whose SuppressionOptions block was never PUT must
        // fall back to account-level reasons — same filtering behaviour as a
        // send without any configuration set.
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-default"), "us-east-1");

        service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null, null,
                "Subject", "text body", null, "cs-default", List.of(), List.of(), null, "us-east-1");

        verify(smtpRelay, never()).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendRawEmail_csOverridesAccountToEmptyList_suppressionListIsIgnored() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-no-suppression-raw"), "us-east-1");
        service.putConfigurationSetSuppressionOptions("cs-no-suppression-raw", List.of(), "us-east-1");

        service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", null, "cs-no-suppression-raw", List.of(), null,
                "us-east-1");

        assertEquals(List.of("to@example.com"), capturedRawRelay().destinations());
    }
}
