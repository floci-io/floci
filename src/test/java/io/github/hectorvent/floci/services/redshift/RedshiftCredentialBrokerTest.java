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
    void issueKeepsEveryUnexpiredCredentialForSameUser() {
        TempCredential first = broker.issue("acc", "clus", "analyst", List.of(), 900);
        TempCredential second = broker.issue("acc", "clus", "analyst", List.of(), 900);

        // AWS keeps each issued password valid until its own expiry: a reissue must not
        // invalidate a still-live credential.
        assertEquals(RedshiftCredentialBroker.Match.MASTER_EQUIVALENT,
                broker.classify("acc", "clus", "analyst", first.password()));
        assertEquals(RedshiftCredentialBroker.Match.MASTER_EQUIVALENT,
                broker.classify("acc", "clus", "analyst", second.password()));
    }

    @Test
    void issuePrunesExpiredCredentialsForSameUser() {
        broker.issue("acc", "clus", "analyst", List.of(), 0);
        TempCredential live = broker.issue("acc", "clus", "analyst", List.of(), 900);

        assertEquals(RedshiftCredentialBroker.Match.MASTER_EQUIVALENT,
                broker.classify("acc", "clus", "analyst", live.password()));
        assertEquals(1, broker.liveCredentialCountForTesting("acc", "clus", "analyst"));
    }

    @Test
    void revokeClusterDropsEveryCredentialForThatCluster() {
        TempCredential a = broker.issue("acc", "clus", "analyst", List.of(), 900);
        broker.issue("acc", "clus", "etl", List.of(), 900);
        TempCredential other = broker.issue("acc", "other", "analyst", List.of(), 900);

        broker.revokeCluster("acc", "clus");

        assertTrue(broker.resolve("acc", "clus", "analyst").isEmpty());
        assertEquals(RedshiftCredentialBroker.Match.PASSTHROUGH,
                broker.classify("acc", "clus", "analyst", a.password()));
        assertEquals(RedshiftCredentialBroker.Match.PASSTHROUGH,
                broker.classify("acc", "clus", "etl", "anything"));
        // A same-named DbUser on a different cluster is untouched.
        assertEquals(RedshiftCredentialBroker.Match.MASTER_EQUIVALENT,
                broker.classify("acc", "other", "analyst", other.password()));
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
