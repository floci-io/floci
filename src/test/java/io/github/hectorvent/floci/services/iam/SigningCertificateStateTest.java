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
import io.github.hectorvent.floci.services.iam.model.CredentialReport;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.SigningCertificate;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 * The properties of the signing-certificate store the wire tests cannot reach: that it survives a
 * restart, that one account cannot see another's, that the quota holds when uploads race, and that
 * the credentials a user owns follow the user through a rename and block a delete.
 */
class SigningCertificateStateTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    /** EC: these tests exercise locking and storage, never the key algorithm. */
    private static String certificatePem() {
        return GENERATOR.generateSelfSignedCertificate(
                "state.test.local", List.of(), KeyAlgorithm.EC_prime256v1).certificatePem();
    }

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    private static IamService newService(StorageBackend<String, SigningCertificate> certificates,
                                         String defaultAccount) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                certificates,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    private static IamService newService(StorageBackend<String, IamUser> users,
                                         StorageBackend<String, CredentialReport> reports,
                                         StorageBackend<String, SigningCertificate> certificates,
                                         String defaultAccount) {
        return new IamService(
                users, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), reports, new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                certificates,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    @Test
    void aCertificateSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("signing-certificates.json");
        String body = certificatePem();

        PersistentStorage<String, SigningCertificate> first =
                new PersistentStorage<>(file, new TypeReference<>() {});
        first.load();
        IamService before = newService(first, ACCOUNT_A);
        before.createUser("restart-user", "/");
        String id = before.uploadSigningCertificate("restart-user", body).getCertificateId();
        before.updateSigningCertificate("restart-user", id, "Inactive");

        PersistentStorage<String, SigningCertificate> second =
                new PersistentStorage<>(file, new TypeReference<>() {});
        second.load();
        IamService after = newService(second, ACCOUNT_A);
        after.createUser("restart-user", "/");
        List<SigningCertificate> reloaded = after.listSigningCertificates("restart-user");

        assertEquals(1, reloaded.size());
        assertEquals(id, reloaded.getFirst().getCertificateId());
        assertEquals(body, reloaded.getFirst().getCertificateBody());
        // The status is the part most likely to be dropped on the way through, because a default
        // of Active would hide the loss.
        assertEquals("Inactive", reloaded.getFirst().getStatus());
    }

    @Test
    void oneAccountCannotSeeAnothersCertificates() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<SigningCertificate> store = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService service = newService(store, ACCOUNT_A);

        service.createUser("shared-name", "/");
        service.uploadSigningCertificate("shared-name", certificatePem());
        assertEquals(1, service.listSigningCertificates("shared-name").size());

        // The users store here is not account-aware, so the user is already present under the
        // same name. That is what makes this discriminating: only the certificate store is
        // scoped, so a leak would show up as account B seeing A's certificate.
        account.set(ACCOUNT_B);
        assertEquals(0, service.listSigningCertificates("shared-name").size(),
                "account B must not see account A's certificate");

        // And B filling its own quota must not be refused because A already has certificates.
        service.uploadSigningCertificate("shared-name", certificatePem());
        service.uploadSigningCertificate("shared-name", certificatePem());
        assertEquals(2, service.listSigningCertificates("shared-name").size());

        account.set(ACCOUNT_A);
        assertEquals(1, service.listSigningCertificates("shared-name").size(),
                "account A's view must be unchanged by account B's uploads");
    }

    /**
     * The quota is counted inside the same lock as the write, so concurrent uploads cannot both
     * see room for the second-to-last slot. Asserted by counting what actually succeeded, because
     * an invariant phrased as "the store holds at most two" passes even with the lock removed.
     */
    @Test
    void concurrentUploadsCannotExceedTheQuota() throws Exception {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("race-user", "/");

        int attempts = 12;
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            bodies.add(certificatePem());
        }
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                String body = bodies.get(i);
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSigningCertificate("race-user", body);
                        accepted.incrementAndGet();
                    } catch (AwsException expected) {
                        // LimitExceeded for the losers, which is the point of the race.
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "uploads did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, accepted.get(), "exactly the quota should have been accepted");
        assertEquals(2, service.listSigningCertificates("race-user").size());
    }

    /**
     * The quota race above uses a different certificate per thread, so it never exercises the
     * duplicate check. Uploading the same body from several threads has to leave exactly one
     * stored.
     *
     * <p>This one does not discriminate the lock: with the upload's lock made per-call it still
     * passes, because the first write lands long before the other threads finish parsing the
     * stored material to compare it. The lock is covered by the quota race, which does fail
     * without it. Kept because it pins the outcome rather than the mechanism.
     */
    @Test
    void concurrentUploadsOfTheSameCertificateLeaveExactlyOne() throws Exception {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("dup-race", "/");
        String body = certificatePem();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSigningCertificate("dup-race", body);
                        accepted.incrementAndGet();
                    } catch (AwsException expected) {
                        // DuplicateCertificate for the losers.
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "uploads did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get(), "only one upload of the same certificate may succeed");
        assertEquals(1, service.listSigningCertificates("dup-race").size());
    }

    /**
     * An upload must never land on a user DeleteUser has just removed. Both hold the signing lock,
     * so only two orders exist: the delete is refused because a certificate is there, or the upload
     * fails because the user is gone. A certificate owned by a user that does not exist is the
     * state neither order may produce, and nothing in the API would ever report it: the owner
     * cannot list it, because listing goes through the user.
     *
     * <p>Asserted against the store rather than through the service, for that reason.
     */
    @Test
    void anUploadNeverStrandsACertificateOnADeletedUser() throws Exception {
        for (int trial = 0; trial < 300; trial++) {
            InMemoryStorage<String, SigningCertificate> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("delete-race", "/");
            String body = certificatePem();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSigningCertificate("delete-race", body);
                    } catch (AwsException expected) {
                        // NoSuchEntity when the delete won.
                    }
                    return null;
                });
                pool.submit(() -> {
                    start.await();
                    try {
                        service.deleteUser("delete-race");
                    } catch (AwsException expected) {
                        // DeleteConflict when the upload won.
                    }
                    return null;
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }

            boolean userExists;
            try {
                service.getUser("delete-race");
                userExists = true;
            } catch (AwsException gone) {
                userExists = false;
            }
            List<SigningCertificate> stored = store.scan(key -> true);
            if (!userExists) {
                assertTrue(stored.isEmpty(),
                        "trial " + trial + ": certificate left behind on a deleted user");
            }
        }
    }

    /**
     * The same hazard on the rename path: a certificate uploaded while a rename is in flight must
     * end up under whichever name the user actually has, never stranded on the old one.
     */
    @Test
    void anUploadNeverStrandsACertificateOnTheOldNameDuringARename() throws Exception {
        for (int trial = 0; trial < 300; trial++) {
            InMemoryStorage<String, SigningCertificate> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("rename-race-from", "/");
            String body = certificatePem();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSigningCertificate("rename-race-from", body);
                    } catch (AwsException expected) {
                        // NoSuchEntity when the rename landed first.
                    }
                    return null;
                });
                pool.submit(() -> {
                    start.await();
                    service.updateUser("rename-race-from", "rename-race-to", null);
                    return null;
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }

            for (SigningCertificate certificate : store.scan(key -> true)) {
                assertEquals("rename-race-to", certificate.getUserName(),
                        "trial " + trial + ": certificate stranded on the pre-rename name");
            }
        }
    }

    /** AWS lists the signing certificate among the things that must be removed before DeleteUser. */
    @Test
    void aUserWithACertificateCannotBeDeleted() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("blocked-user", "/");
        String id = service.uploadSigningCertificate("blocked-user", certificatePem())
                .getCertificateId();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.deleteUser("blocked-user"));
        assertEquals("DeleteConflict", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("signing certificate"),
                "the message should name what is in the way: " + refused.getMessage());

        service.deleteSigningCertificate("blocked-user", id);
        service.deleteUser("blocked-user");
    }

    /**
     * A rename must carry the certificates with it. Left behind, a certificate is stranded on a
     * user name that no longer exists: invisible to its owner and undeletable.
     */
    @Test
    void aRenameCarriesTheCertificates() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("before-rename", "/");
        String id = service.uploadSigningCertificate("before-rename", certificatePem())
                .getCertificateId();

        service.updateUser("before-rename", "after-rename", null);

        List<SigningCertificate> moved = service.listSigningCertificates("after-rename");
        assertEquals(1, moved.size(), "the certificate should have followed the rename");
        assertEquals(id, moved.getFirst().getCertificateId());
        assertEquals("after-rename", moved.getFirst().getUserName());
        // And the rename must not leave the user undeletable for a certificate it cannot see.
        service.deleteSigningCertificate("after-rename", id);
        service.deleteUser("after-rename");
    }

    /**
     * The credential report's cert columns, which the User Guide defines precisely: TRUE only for
     * an Active certificate, and N/A for the rotation date when the user has no active one.
     */
    @Test
    void theCredentialReportReflectsCertificateStatus() {
        StorageBackend<String, IamUser> users = new InMemoryStorage<>();
        StorageBackend<String, SigningCertificate> certificates = new InMemoryStorage<>();
        IamService service = newService(users, new InMemoryStorage<>(), certificates, ACCOUNT_A);
        service.createUser("report-user", "/");
        String id = service.uploadSigningCertificate("report-user", certificatePem())
                .getCertificateId();

        String active = reportRowFor(service, "report-user");
        assertTrue(active.contains("TRUE"), "an active certificate should report TRUE: " + active);

        service.updateSigningCertificate("report-user", id, "Inactive");
        // GenerateCredentialReport reuses a report generated in the past four hours, so the
        // second row has to come from a service that has not generated one yet.
        IamService regenerated = newService(users, new InMemoryStorage<>(), certificates, ACCOUNT_A);
        String inactive = reportRowFor(regenerated, "report-user");
        String[] columns = inactive.split(",", -1);
        // cert_1_active is FALSE, and cert_1_last_rotated is N/A rather than the upload date:
        // "If the user does not have an active signing certificate, the value in this field is N/A".
        int certActive = columns.length - 5;
        assertEquals("FALSE", columns[certActive], "cert_1_active for an Inactive certificate");
        assertEquals("N/A", columns[certActive + 1],
                "cert_1_last_rotated must be N/A when no active certificate exists");
    }

    private static String reportRowFor(IamService service, String userName) {
        service.generateCredentialReport();
        String csv = new String(Base64.getDecoder()
                .decode(service.getCredentialReport().base64Content()), StandardCharsets.UTF_8);
        return csv.lines()
                .filter(line -> line.startsWith(userName + ","))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no report row for " + userName));
    }
}
