package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The properties of the server-certificate store that the wire tests cannot reach: that it survives
 * a restart, that one account cannot see another's, and that its check-then-write paths hold when
 * two requests race.
 *
 * <p>A server certificate holds a private key, so account isolation here is the same class of
 * concern as an MFA seed: a leak across accounts would hand over usable key material.
 */
class ServerCertificateStateTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static CertificateGenerator.GeneratedCertificate material() {
        return GENERATOR.generateSelfSignedCertificate("state.test.local", List.of(), KeyAlgorithm.RSA_2048);
    }

    /**
     * For the trial-heavy tests below. What they exercise is the locking, which does not care which
     * algorithm the key uses, and an EC key is generated far more cheaply than an RSA one: using
     * RSA here cost most of this class's runtime for no extra coverage.
     */
    private static CertificateGenerator.GeneratedCertificate cheapMaterial() {
        return GENERATOR.generateSelfSignedCertificate("state.test.local", List.of(),
                KeyAlgorithm.EC_prime256v1);
    }

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    private static IamService newService(StorageBackend<String, ServerCertificate> certificates,
                                         String defaultAccount) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                certificates,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    /**
     * The private key and the expiry both have to survive a restart. The expiry is read from the
     * certificate at upload, so if it failed to round trip the certificate would come back
     * claiming no expiry at all rather than visibly breaking.
     */
    @Test
    void aServerCertificateSurvivesRestart(@TempDir Path dir) {
        PersistentStorage<String, ServerCertificate> first =
                new PersistentStorage<>(dir.resolve("iam-server-certificates.json"), new TypeReference<>() {});
        first.load();
        CertificateGenerator.GeneratedCertificate pair = material();
        ServerCertificate created = newService(first, ACCOUNT_A)
                .uploadServerCertificate("persisted", "/team/", pair.certificatePem(),
                        pair.privateKeyPem(), null, Map.of("env", "prod"));

        PersistentStorage<String, ServerCertificate> reopened =
                new PersistentStorage<>(dir.resolve("iam-server-certificates.json"), new TypeReference<>() {});
        reopened.load();
        ServerCertificate reloaded = newService(reopened, ACCOUNT_A).getServerCertificate("persisted");

        assertEquals(created.getCertificateBody(), reloaded.getCertificateBody());
        assertEquals(created.getPrivateKey(), reloaded.getPrivateKey(), "the private key must survive");
        assertEquals(created.getServerCertificateId(), reloaded.getServerCertificateId());
        assertEquals(created.getArn(), reloaded.getArn());
        assertEquals("/team/", reloaded.getPath());
        assertEquals("prod", reloaded.getTags().get("env"));
        assertEquals(created.getExpiration(), reloaded.getExpiration(), "the expiry must survive");
    }

    @Test
    void anotherAccountCanNeitherSeeNorReachACertificate() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<ServerCertificate> certificates = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService service = newService(certificates, ACCOUNT_A);
        CertificateGenerator.GeneratedCertificate pair = material();
        service.uploadServerCertificate("scoped", "/", pair.certificatePem(), pair.privateKeyPem(),
                null, Map.of());

        account.set(ACCOUNT_B);
        assertTrue(service.listServerCertificates(null).isEmpty(),
                "account B must not see account A's certificates");
        assertEquals("NoSuchEntity",
                assertThrows(AwsException.class, () -> service.getServerCertificate("scoped")).getErrorCode());
        assertEquals("NoSuchEntity",
                assertThrows(AwsException.class, () -> service.deleteServerCertificate("scoped")).getErrorCode());
        // The key must not be reachable through the tag reader either.
        assertThrows(AwsException.class, () -> service.listServerCertificateTags("scoped"));

        // Account B may use the same name without colliding, and that must not disturb A's copy.
        CertificateGenerator.GeneratedCertificate other = material();
        service.uploadServerCertificate("scoped", "/", other.certificatePem(), other.privateKeyPem(),
                null, Map.of());
        assertEquals(other.privateKeyPem(), service.getServerCertificate("scoped").getPrivateKey());

        account.set(ACCOUNT_A);
        assertEquals(pair.privateKeyPem(), service.getServerCertificate("scoped").getPrivateKey(),
                "account A's key was replaced by account B's upload");
    }

    private static int raceTogether(Runnable... actions) throws InterruptedException {
        AtomicInteger succeeded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(actions.length);
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            for (Runnable action : actions) {
                pool.submit(() -> {
                    try {
                        start.await();
                        action.run();
                        succeeded.incrementAndGet();
                    } catch (AwsException expected) {
                        // One side of a race is meant to lose; the count is the assertion.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "actions did not finish");
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    /** Two uploads of one name: the loser must be told, not silently replace the winner's key. */
    @Test
    void onlyOneOfTwoUploadsOfTheSameNameSucceeds() throws InterruptedException {
        for (int trial = 0; trial < 100; trial++) {
            IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
            CertificateGenerator.GeneratedCertificate a = cheapMaterial();
            CertificateGenerator.GeneratedCertificate b = cheapMaterial();

            int won = raceTogether(
                    () -> service.uploadServerCertificate("contended", "/", a.certificatePem(),
                            a.privateKeyPem(), null, Map.of()),
                    () -> service.uploadServerCertificate("contended", "/", b.certificatePem(),
                            b.privateKeyPem(), null, Map.of()));

            assertEquals(1, won, "both uploads were told they stored the certificate");
            assertEquals(1, service.listServerCertificates(null).size());
        }
    }

    /**
     * Concurrent uploads must not push the account past its quota. Counted and written under one
     * lock, exactly 20 can land; an unguarded count would let several read 19 together.
     */
    @Test
    void theAccountQuotaHoldsUnderConcurrentUploads() throws InterruptedException {
        for (int trial = 0; trial < 10; trial++) {
            IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
            Runnable[] uploads = new Runnable[24];
            for (int i = 0; i < uploads.length; i++) {
                String name = "quota-" + i;
                CertificateGenerator.GeneratedCertificate pair = cheapMaterial();
                uploads[i] = () -> service.uploadServerCertificate(name, "/", pair.certificatePem(),
                        pair.privateKeyPem(), null, Map.of());
            }

            raceTogether(uploads);

            assertEquals(20, service.listServerCertificates(null).size(),
                    "the per-account quota let more than 20 certificates through");
        }
    }

    /** Renaming must not lose the material, the id, the expiry or the tags. */
    @Test
    void renameKeepsEverythingButTheNameAndArn() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        CertificateGenerator.GeneratedCertificate pair = material();
        ServerCertificate before = service.uploadServerCertificate("before", "/", pair.certificatePem(),
                pair.privateKeyPem(), pair.certificatePem(), Map.of("env", "prod"));
        String originalId = before.getServerCertificateId();
        Instant originalExpiry = before.getExpiration();

        service.updateServerCertificate("before", "after", "/moved/");

        ServerCertificate after = service.getServerCertificate("after");
        assertEquals(originalId, after.getServerCertificateId(), "the id is immutable across a rename");
        assertEquals(originalExpiry, after.getExpiration());
        assertEquals(pair.privateKeyPem(), after.getPrivateKey());
        assertEquals(pair.certificatePem(), after.getCertificateChain());
        assertEquals("prod", after.getTags().get("env"));
        assertEquals("/moved/", after.getPath());
        assertTrue(after.getArn().endsWith(":server-certificate/moved/after"), after.getArn());
        assertEquals("NoSuchEntity",
                assertThrows(AwsException.class, () -> service.getServerCertificate("before")).getErrorCode());
    }

    /**
     * A rename racing a delete. Counting entries is not enough: that still passes if the rename
     * reports success while losing the material, or if a worker dies on something other than an
     * AwsException. So each side's outcome is recorded and checked against the state it implies.
     */
    @Test
    void renamingNeverRacesADeleteIntoLosingTheCertificate() throws InterruptedException {
        List<String> broken = new CopyOnWriteArrayList<>();
        for (int trial = 0; trial < 100; trial++) {
            IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
            CertificateGenerator.GeneratedCertificate pair = cheapMaterial();
            service.uploadServerCertificate("from", "/", pair.certificatePem(), pair.privateKeyPem(),
                    null, Map.of());
            AtomicReference<String> renameOutcome = new AtomicReference<>("not-run");
            AtomicReference<String> deleteOutcome = new AtomicReference<>("not-run");

            raceTogether(
                    () -> renameOutcome.set(record(() -> service.updateServerCertificate("from", "to", null))),
                    () -> deleteOutcome.set(record(() -> service.deleteServerCertificate("from"))));

            String state = "trial " + trial + " rename=" + renameOutcome.get()
                    + " delete=" + deleteOutcome.get();
            if (renameOutcome.get().startsWith("threw:") || deleteOutcome.get().startsWith("threw:")) {
                broken.add(state + " (unexpected exception)");
                continue;
            }
            List<ServerCertificate> remaining = service.listServerCertificates(null);
            if (remaining.size() > 1) {
                broken.add(state + " left " + remaining.size() + " entries");
            } else if ("ok".equals(renameOutcome.get())) {
                // The rename won, so the certificate must exist under the new name, intact.
                if (remaining.size() != 1 || !"to".equals(remaining.get(0).getServerCertificateName())
                        || !pair.privateKeyPem().equals(remaining.get(0).getPrivateKey())) {
                    broken.add(state + " renamed but the material did not follow");
                }
            } else if ("ok".equals(deleteOutcome.get()) && !remaining.isEmpty()) {
                broken.add(state + " deleted but a certificate remains");
            }
        }
        assertTrue(broken.isEmpty(), "rename racing delete left inconsistent state: " + broken);
    }

    /** Runs an action, reporting "ok", its AWS error code, or an unexpected throwable. */
    private static String record(Runnable action) {
        try {
            action.run();
            return "ok";
        } catch (AwsException e) {
            return e.getErrorCode();
        } catch (RuntimeException e) {
            return "threw:" + e.getClass().getSimpleName();
        }
    }
}
