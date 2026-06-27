package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
        emailStore = new InMemoryStorage<>();
        service = new SesService(
                new InMemoryStorage<String, Identity>(),
                emailStore,
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, EmailTemplate>(),
                new InMemoryStorage<String, ConfigurationSet>(),
                new InMemoryStorage<String, SuppressedDestination>(),
                new InMemoryStorage<String, AccountSuppressionAttributes>(),
                new InMemoryStorage<String, DedicatedIpPool>(),
                new InMemoryStorage<String, ContactList>(),
                smtpRelay,
                new ObjectMapper(),
                Clock.systemUTC());
    }

    @Test
    void sendEmail_callsRelayWithAllFields() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                List.of("reply@example.com"),
                "Subject", "text body", "<p>html</p>", null, List.of(), List.of(), "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                List.of("reply@example.com"),
                "Subject", "text body", "<p>html</p>");
    }

    @Test
    void sendEmail_storesAndRelays() {
        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null,
                "Subject", "text", null, null, List.of(), List.of(), "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relay(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendRawEmail_callsRelayRaw() {
        service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", null, List.of(), "us-east-1");

        verify(smtpRelay).relayRaw(
                "from@example.com",
                List.of("to@example.com"),
                "raw MIME");
    }

    @Test
    void sendRawEmail_storesAndRelays() {
        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw", null, List.of(), "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relayRaw(any(), any(), any());
    }

    @Test
    void sendEmail_relayReceivesCorrectFieldsWithNulls() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"),
                null, null, null,
                "Subject", null, "<p>html only</p>", null, List.of(), List.of(), "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("to@example.com"),
                null, null, null,
                "Subject", null, "<p>html only</p>");
    }

    @Test
    void sendEmail_allRecipientsSuppressed_skipsRelayButStillStores() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.putSuppressedDestination("us-east-1", "cc@example.com", "COMPLAINT");

        String messageId = service.sendEmail("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                null, null,
                "Subject", "text body", null, null, List.of(), List.of(), "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty(),
                "stored SentEmail should still record the original recipient list");
        verify(smtpRelay, never()).relay(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendEmail_partialSuppression_relayCalledWithFilteredRecipients() {
        // Only suppress one of the To recipients; the other should still reach the relay.
        service.putSuppressedDestination("us-east-1", "suppressed@example.com", "BOUNCE");

        service.sendEmail("from@example.com",
                List.of("to@example.com", "suppressed@example.com"),
                List.of("cc-keep@example.com"),
                null, null,
                "Subject", "text body", null, null, List.of(), List.of(), "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("to@example.com"),
                List.of("cc-keep@example.com"),
                null,
                null,
                "Subject", "text body", null);
    }

    @Test
    void sendRawEmail_allRecipientsSuppressed_skipsRelayRawButStillStores() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");

        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", null, List.of(), "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay, never()).relayRaw(any(), any(), any());
    }

    @Test
    void sendRawEmail_partialSuppression_relayRawCalledWithFilteredRecipients() {
        service.putSuppressedDestination("us-east-1", "suppressed@example.com", "COMPLAINT");

        service.sendRawEmail("from@example.com",
                List.of("to@example.com", "suppressed@example.com"),
                "raw MIME", null, List.of(), "us-east-1");

        verify(smtpRelay).relayRaw(
                "from@example.com",
                List.of("to@example.com"),
                "raw MIME");
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
                List.of("to@example.com"), null, null, null,
                "Subject", "text body", null, "cs-no-suppression", List.of(), List.of(), "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("to@example.com"),
                null, null, null,
                "Subject", "text body", null);
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
                List.of("complainer@example.com"), null, null, null,
                "Subject", "text body", null, "cs-bounce-only", List.of(), List.of(), "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("complainer@example.com"),
                null, null, null,
                "Subject", "text body", null);
    }

    @Test
    void sendEmail_csWithoutOverride_fallsBackToAccountLevelSuppression() {
        // A configuration set whose SuppressionOptions block was never PUT must
        // fall back to account-level reasons — same filtering behaviour as a
        // send without any configuration set.
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-default"), "us-east-1");

        service.sendEmail("from@example.com",
                List.of("to@example.com"), null, null, null,
                "Subject", "text body", null, "cs-default", List.of(), List.of(), "us-east-1");

        verify(smtpRelay, never()).relay(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendRawEmail_csOverridesAccountToEmptyList_suppressionListIsIgnored() {
        service.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-no-suppression-raw"), "us-east-1");
        service.putConfigurationSetSuppressionOptions("cs-no-suppression-raw", List.of(), "us-east-1");

        service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", "cs-no-suppression-raw", List.of(), "us-east-1");

        verify(smtpRelay).relayRaw(
                "from@example.com",
                List.of("to@example.com"),
                "raw MIME");
    }
}
