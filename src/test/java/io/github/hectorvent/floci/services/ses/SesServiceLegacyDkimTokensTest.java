package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesServiceLegacyDkimTokensTest {

    private static final String REGION = "us-east-1";

    private SesService service;
    private InMemoryStorage<String, Identity> identityStore;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        identityStore = builder.identityStore();
        service = builder.build();
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
        // Once tokens are backfilled the domain is pending DNS detection (status tracks detection, not
        // the signing flag), rather than staying NotStarted.
        assertEquals("Pending", refreshed.getDkimVerificationStatus());

        List<String> persistedTokens = identityStore.get(key).orElseThrow().getDkimTokens();
        assertNotNull(persistedTokens);
        assertEquals(refreshed.getDkimTokens(), persistedTokens);
    }
}
