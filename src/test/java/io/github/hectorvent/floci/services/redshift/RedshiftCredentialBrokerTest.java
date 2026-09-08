package io.github.hectorvent.floci.services.redshift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCredentialBrokerTest {

    private RedshiftCredentialBroker broker;

    @BeforeEach
    void setUp() {
        broker = new RedshiftCredentialBroker();
    }

    @Test
    void issueReturnsPopulatedCredentialWithFutureExpiry() {
        TempCredential cred = broker.issue("acc", "clus", "analyst", List.of("etl"), 900);

        assertEquals("analyst", cred.dbUser());
        assertFalse(cred.password().isBlank());
        assertTrue(cred.expiresAt().isAfter(Instant.now()));
        assertEquals(List.of("etl"), cred.dbGroups());
    }

    @Test
    void resolveReturnsLiveCredential() {
        broker.issue("acc", "clus", "analyst", List.of(), 900);

        Optional<TempCredential> found = broker.resolve("acc", "clus", "analyst");

        assertTrue(found.isPresent());
        assertEquals("analyst", found.get().dbUser());
    }

    @Test
    void resolveMissesForUnknownUser() {
        assertTrue(broker.resolve("acc", "clus", "nobody").isEmpty());
    }

    @Test
    void resolveEvictsExpiredCredential() {
        broker.issue("acc", "clus", "analyst", List.of(), 0);

        assertTrue(broker.resolve("acc", "clus", "analyst").isEmpty());
    }

    @Test
    void issueOverwritesPreviousCredentialForSameUser() {
        TempCredential first = broker.issue("acc", "clus", "analyst", List.of(), 900);
        TempCredential second = broker.issue("acc", "clus", "analyst", List.of(), 900);

        assertTrue(first.password() != null && second.password() != null);
        Optional<TempCredential> live = broker.resolve("acc", "clus", "analyst");
        assertTrue(live.isPresent());
        assertEquals(second.password(), live.get().password());
    }

    @Test
    void classifyMatchesMasterEquivalentOnCorrectPassword() {
        TempCredential cred = broker.issue("acc", "clus", "analyst", List.of(), 900);

        assertEquals(RedshiftCredentialBroker.Match.MASTER_EQUIVALENT,
                broker.classify("acc", "clus", "analyst", cred.password()));
    }

    @Test
    void classifyRejectsKnownUserWithWrongPassword() {
        broker.issue("acc", "clus", "analyst", List.of(), 900);

        assertEquals(RedshiftCredentialBroker.Match.REJECT,
                broker.classify("acc", "clus", "analyst", "wrong"));
    }

    @Test
    void classifyPassesThroughUnknownUser() {
        assertEquals(RedshiftCredentialBroker.Match.PASSTHROUGH,
                broker.classify("acc", "clus", "stranger", "whatever"));
    }

    @Test
    void clearRemovesAllCredentials() {
        broker.issue("acc", "clus", "analyst", List.of(), 900);
        broker.clear();
        assertTrue(broker.resolve("acc", "clus", "analyst").isEmpty());
    }
}
