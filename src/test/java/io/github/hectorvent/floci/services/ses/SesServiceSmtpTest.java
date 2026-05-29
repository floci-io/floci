package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                smtpRelay,
                new ObjectMapper());
    }

    @Test
    void sendEmail_callsRelayWithAllFields() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"),
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                List.of("reply@example.com"),
                "Subject", "text body", "<p>html</p>", "us-east-1");

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
                "Subject", "text", null, "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relay(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendRawEmail_callsRelayRaw() {
        service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw MIME", "us-east-1");

        verify(smtpRelay).relayRaw(
                "from@example.com",
                List.of("to@example.com"),
                "raw MIME");
    }

    @Test
    void sendRawEmail_storesAndRelays() {
        String messageId = service.sendRawEmail("from@example.com",
                List.of("to@example.com"), "raw", "us-east-1");

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relayRaw(any(), any(), any());
    }

    @Test
    void sendEmail_relayReceivesCorrectFieldsWithNulls() {
        service.sendEmail("from@example.com",
                List.of("to@example.com"),
                null, null, null,
                "Subject", null, "<p>html only</p>", "us-east-1");

        verify(smtpRelay).relay(
                "from@example.com",
                List.of("to@example.com"),
                null, null, null,
                "Subject", null, "<p>html only</p>");
    }
}
