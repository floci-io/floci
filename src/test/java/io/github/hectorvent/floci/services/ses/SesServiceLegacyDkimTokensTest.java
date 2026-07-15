package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SesServiceLegacyDkimTokensTest {

    private static final String REGION = "us-east-1";

    private SesService service;
    private InMemoryStorage<String, Identity> identityStore;

    @BeforeEach
    void setUp() {
        identityStore = new InMemoryStorage<>();
        service = new SesService(
                identityStore,
                new InMemoryStorage<String, SentEmail>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, EmailTemplate>(),
                new InMemoryStorage<String, ConfigurationSet>(),
                new InMemoryStorage<String, SuppressedDestination>(),
                new InMemoryStorage<String, AccountSuppressionAttributes>(),
                new InMemoryStorage<String, DedicatedIpPool>(),
                new InMemoryStorage<String, ContactList>(),
                new InMemoryStorage<String, Contact>(),
                mock(SmtpRelay.class),
                new ObjectMapper(),
                Clock.systemUTC());
    }

    @Test
    void getIdentityVerificationAttributes_backfillsLegacyDomainDkimTokens() {
        Identity legacy = new Identity("legacy.floci.test", "Domain");
        legacy.setVerificationStatus("Pending");
        legacy.setDkimEnabled(false);
        legacy.setDkimVerificationStatus("NotStarted");
        legacy.setDkimTokens(null);
        assertNull(legacy.getDkimTokens());

        String key = "identity::" + REGION + "::" + legacy.getIdentity();
        identityStore.put(key, legacy);

        Identity refreshed = service.getIdentityVerificationAttributes(legacy.getIdentity(), REGION);

        assertSame(legacy, refreshed);
        assertNotNull(refreshed.getDkimTokens());
        assertEquals(3, refreshed.getDkimTokens().size());
        assertTrue(refreshed.getDkimTokens().stream().allMatch(token -> token != null && !token.isBlank()));
        assertEquals("Pending", refreshed.getVerificationStatus());
        assertFalse(refreshed.isDkimEnabled());
        assertEquals("NotStarted", refreshed.getDkimVerificationStatus());

        List<String> persistedTokens = identityStore.get(key).orElseThrow().getDkimTokens();
        assertNotNull(persistedTokens);
        assertEquals(refreshed.getDkimTokens(), persistedTokens);
    }
}
