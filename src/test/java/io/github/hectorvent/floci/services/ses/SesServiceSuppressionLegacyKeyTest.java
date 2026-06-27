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

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Backward-compat for suppression entries persisted by a pre-canonicalization Floci,
 * which stored the address trim-only — so an entry with an upper-case domain lives under
 * a key the new domain-lower-casing lookup would otherwise miss. GET/DELETE fall back to
 * the legacy key, and PUT migrates it onto the canonical key. Only reachable by seeding
 * the store directly (the HTTP API always normalizes), so it lives at the unit layer.
 */
class SesServiceSuppressionLegacyKeyTest {

    private static final String REGION = "us-east-1";
    // A pre-canonicalization entry: trim-only key with an upper-case domain.
    private static final String LEGACY_ADDR = "Foo.Bar@Example.COM";
    private static final String LEGACY_KEY = "suppression::" + REGION + "::" + LEGACY_ADDR;
    private static final String CANONICAL_KEY = "suppression::" + REGION + "::Foo.Bar@example.com";

    private SesService service;
    private InMemoryStorage<String, SuppressedDestination> suppressionStore;

    @BeforeEach
    void setUp() {
        suppressionStore = new InMemoryStorage<>();
        service = new SesService(
                new InMemoryStorage<String, Identity>(),
                new InMemoryStorage<String, SentEmail>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, EmailTemplate>(),
                new InMemoryStorage<String, ConfigurationSet>(),
                suppressionStore,
                new InMemoryStorage<String, AccountSuppressionAttributes>(),
                new InMemoryStorage<String, DedicatedIpPool>(),
                new InMemoryStorage<String, ContactList>(),
                mock(SmtpRelay.class),
                new ObjectMapper(),
                Clock.systemUTC());
    }

    private void seedLegacyEntry() {
        suppressionStore.put(LEGACY_KEY, new SuppressedDestination(LEGACY_ADDR, "BOUNCE"));
    }

    @Test
    void getReachesLegacyEntryByExactAddress() {
        seedLegacyEntry();
        SuppressedDestination got = service.getSuppressedDestination(REGION, LEGACY_ADDR);
        assertEquals(LEGACY_ADDR, got.getEmailAddress());
        assertEquals("BOUNCE", got.getReason());
    }

    @Test
    void deleteRemovesLegacyEntry() {
        seedLegacyEntry();
        service.deleteSuppressedDestination(REGION, LEGACY_ADDR);
        assertFalse(suppressionStore.get(LEGACY_KEY).isPresent());
    }

    @Test
    void sendTimeSuppressionReasonReachesLegacyEntry() {
        // The event / send-time path (resolveSuppressionReason, collectSuppressedReasons)
        // must also honor the legacy key, or a legacy entry stays deletable but no longer
        // suppresses sends. Default fresh account suppresses [BOUNCE, COMPLAINT].
        seedLegacyEntry();
        assertEquals("BOUNCE", service.resolveSuppressionReason(LEGACY_ADDR, null, REGION));
    }

    @Test
    void putMigratesLegacyEntryOntoCanonicalKeyWithoutDuplicate() {
        seedLegacyEntry();
        service.putSuppressedDestination(REGION, LEGACY_ADDR, "COMPLAINT");
        assertFalse(suppressionStore.get(LEGACY_KEY).isPresent(), "legacy key should be migrated away");
        assertTrue(suppressionStore.get(CANONICAL_KEY).isPresent(), "entry should live under canonical key");
        SuppressedDestination migrated = suppressionStore.get(CANONICAL_KEY).orElseThrow();
        assertEquals("Foo.Bar@example.com", migrated.getEmailAddress());
        assertEquals("COMPLAINT", migrated.getReason());
    }
}
