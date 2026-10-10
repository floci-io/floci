package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.SshPublicKeys;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.SshPublicKey;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
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
 * The properties of the SSH public key store the wire tests cannot reach: that it survives a
 * restart, that one account cannot see another's, that the quota holds when uploads race, and that
 * a user's keys block a delete and follow a rename.
 */
class SshPublicKeyStateTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";

    private static RSAPublicKey generated() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return (RSAPublicKey) generator.generateKeyPair().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException("could not generate a key", e);
        }
    }

    private static String openSshKey() {
        return SshPublicKeys.toOpenSsh(generated());
    }

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    private static IamService newService(StorageBackend<String, SshPublicKey> keys,
                                         String defaultAccount) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                keys,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    /**
     * The body is stored as uploaded, so a restart has to return the same bytes rather than a
     * re-encoding of the same key: a caller that uploaded PEM must still get its PEM back.
     */
    @Test
    void aKeySurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("ssh-public-keys.json");
        String pemBody = SshPublicKeys.toPem(generated());

        PersistentStorage<String, SshPublicKey> first =
                new PersistentStorage<>(file, new TypeReference<>() {});
        first.load();
        IamService before = newService(first, ACCOUNT_A);
        before.createUser("restart-user", "/");
        SshPublicKey uploaded = before.uploadSshPublicKey("restart-user", pemBody);
        before.updateSshPublicKey("restart-user", uploaded.getSshPublicKeyId(), "Inactive");

        PersistentStorage<String, SshPublicKey> second =
                new PersistentStorage<>(file, new TypeReference<>() {});
        second.load();
        IamService after = newService(second, ACCOUNT_A);
        after.createUser("restart-user", "/");
        List<SshPublicKey> reloaded = after.listSshPublicKeys("restart-user");

        assertEquals(1, reloaded.size());
        assertEquals(uploaded.getSshPublicKeyId(), reloaded.getFirst().getSshPublicKeyId());
        assertEquals(pemBody, reloaded.getFirst().getSshPublicKeyBody(),
                "the body must come back as it was uploaded, not re-encoded");
        assertEquals(uploaded.getFingerprint(), reloaded.getFirst().getFingerprint());
        // A default of Active would hide the loss of this field, so it is the one to check.
        assertEquals("Inactive", reloaded.getFirst().getStatus());
    }

    @Test
    void oneAccountCannotSeeAnothersKeys() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<SshPublicKey> store = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService service = newService(store, ACCOUNT_A);

        service.createUser("shared-name", "/");
        service.uploadSshPublicKey("shared-name", openSshKey());
        assertEquals(1, service.listSshPublicKeys("shared-name").size());

        // The users store here is not account-aware, so the user is already present under the same
        // name. Only the key store is scoped, so a leak is the only thing that can fail this.
        account.set(ACCOUNT_B);
        assertEquals(0, service.listSshPublicKeys("shared-name").size(),
                "account B must not see account A's key");

        // And B filling its own quota must not be refused for keys A already holds.
        for (int i = 0; i < 5; i++) {
            service.uploadSshPublicKey("shared-name", openSshKey());
        }
        assertEquals(5, service.listSshPublicKeys("shared-name").size());

        account.set(ACCOUNT_A);
        assertEquals(1, service.listSshPublicKeys("shared-name").size(),
                "account A's view must be unchanged by account B's uploads");
    }

    /**
     * The quota is counted inside the same lock as the write, so concurrent uploads cannot both
     * see room for the last slot. Asserted by counting what actually succeeded, because an
     * invariant phrased as "the store holds at most five" passes with the lock removed too.
     */
    @Test
    void concurrentUploadsCannotExceedTheQuota() throws Exception {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("race-user", "/");

        int attempts = 12;
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            bodies.add(openSshKey());
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
                        service.uploadSshPublicKey("race-user", body);
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

        assertEquals(5, accepted.get(), "exactly the quota should have been accepted");
        assertEquals(5, service.listSshPublicKeys("race-user").size());
    }

    /** AWS lists the SSH public key among the items to remove before deleting a user. */
    @Test
    void aUserWithAKeyCannotBeDeleted() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("blocked-user", "/");
        String id = service.uploadSshPublicKey("blocked-user", openSshKey()).getSshPublicKeyId();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.deleteUser("blocked-user"));
        assertEquals("DeleteConflict", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("SSH public key"),
                "the message should name what is in the way: " + refused.getMessage());

        service.deleteSshPublicKey("blocked-user", id);
        service.deleteUser("blocked-user");
    }

    /**
     * A rename must carry the keys with it. Left behind, a key is stranded on a user name that no
     * longer exists: invisible to its owner, because listing goes through the user, and so
     * undeletable.
     */
    @Test
    void aRenameCarriesTheKeys() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("before-rename", "/");
        String id = service.uploadSshPublicKey("before-rename", openSshKey()).getSshPublicKeyId();

        service.updateUser("before-rename", "after-rename", null);

        List<SshPublicKey> moved = service.listSshPublicKeys("after-rename");
        assertEquals(1, moved.size(), "the key should have followed the rename");
        assertEquals(id, moved.getFirst().getSshPublicKeyId());
        assertEquals("after-rename", moved.getFirst().getUserName());
        service.deleteSshPublicKey("after-rename", id);
        service.deleteUser("after-rename");
    }

    /**
     * An upload must never land on a user DeleteUser has just removed. Both hold the SSH lock, so
     * only two orders exist: the delete is refused because a key is there, or the upload fails
     * because the user is gone. A key owned by a user that does not exist is the state neither
     * order may produce, and nothing in the API would report it, so this asserts against the store.
     */
    @Test
    void anUploadNeverStrandsAKeyOnADeletedUser() throws Exception {
        // One key for every trial: each gets a fresh store, so uniqueness is not what is under
        // test, and generating RSA per trial would be the whole runtime.
        String body = openSshKey();
        for (int trial = 0; trial < 60; trial++) {
            InMemoryStorage<String, SshPublicKey> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("delete-race", "/");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSshPublicKey("delete-race", body);
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
            if (!userExists) {
                assertTrue(store.scan(key -> true).isEmpty(),
                        "trial " + trial + ": key left behind on a deleted user");
            }
        }
    }

    /**
     * The same hazard on the rename path. Ran many times because the window between capturing the
     * keys to move and republishing the user is a few statements wide: at thirty trials this
     * passed even with the rename's lock removed, which would have made it a test that guarded
     * nothing.
     */
    @Test
    void anUploadNeverStrandsAKeyOnTheOldNameDuringARename() throws Exception {
        String body = openSshKey();
        for (int trial = 0; trial < 300; trial++) {
            InMemoryStorage<String, SshPublicKey> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("rename-race-from", "/");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.uploadSshPublicKey("rename-race-from", body);
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

            for (SshPublicKey key : store.scan(k -> true)) {
                assertEquals("rename-race-to", key.getUserName(),
                        "trial " + trial + ": key stranded on the pre-rename name");
            }
        }
    }
}
