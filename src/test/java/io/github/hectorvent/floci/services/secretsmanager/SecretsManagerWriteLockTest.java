package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every write to a secret goes through the same per-secret monitor, including the writes a primary
 * fans out to its replicas. These tests park a write halfway through and assert that a second
 * caller aiming at the same secret waits for it, rather than interleaving with it.
 */
class SecretsManagerWriteLockTest {

    private static final String REGION = "us-east-1";
    private static final String REPLICA_REGION = "us-west-2";
    private static final String SECRET_NAME = "shared-secret";

    private ParkingStorage storage;
    private SecretsManagerService service;

    @BeforeEach
    void setUp() {
        storage = new ParkingStorage();
        service = new SecretsManagerService(storage, 30);
        service.createSecret(SECRET_NAME, "v1", null, "original", null, null, REGION);
    }

    @Test
    void updateSecretWaitsForAnInFlightUpdate() throws Exception {
        storage.parkOn(REGION + "::" + SECRET_NAME);
        Watched first = start("first-update", () ->
                service.updateSecret(SECRET_NAME, "from-first", null, REGION));
        assertTrue(storage.awaitEntered(), "UpdateSecret never reached the store write");

        Watched second = start("second-update", () ->
                service.updateSecret(SECRET_NAME, null, "alias/second", REGION));
        assertTrue(second.awaitBlocked(), "UpdateSecret did not wait for the in-flight UpdateSecret");

        storage.release();
        first.awaitSuccess();
        second.awaitSuccess();

        // Serialized, so neither field was dropped: the second update applied on top of the first.
        Secret updated = service.describeSecret(SECRET_NAME, REGION);
        assertEquals("from-first", updated.getDescription());
        assertEquals("alias/second", updated.getKmsKeyId());
    }

    @Test
    void deletingASecretWaitsForAnInFlightUpdate() throws Exception {
        storage.parkOn(REGION + "::" + SECRET_NAME);
        Watched update = start("update", () ->
                service.updateSecret(SECRET_NAME, "from-update", null, REGION));
        assertTrue(storage.awaitEntered(), "UpdateSecret never reached the store write");

        Watched delete = start("delete", () -> service.deleteSecret(SECRET_NAME, null, true, REGION));
        assertTrue(delete.awaitBlocked(), "DeleteSecret did not wait for the in-flight UpdateSecret");

        storage.release();
        update.awaitSuccess();
        delete.awaitSuccess();

        // The delete ran after the update's write, so the secret stays deleted.
        assertThrows(AwsException.class, () -> service.describeSecret(SECRET_NAME, REGION));
    }

    @Test
    void deletingASecretWaitsForTheFinalSaveOfARotation() throws Exception {
        LambdaService lambda = mock(LambdaService.class);
        when(lambda.getFunction(anyString(), anyString())).thenReturn(new LambdaFunction());
        InvokeResult ok = new InvokeResult();
        ok.setStatusCode(200);
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenReturn(ok);
        SecretsManagerService rotating = new SecretsManagerService(
                storage, 30, new RegionResolver(REGION, "000000000000"), lambda, new ObjectMapper());
        try {
            // The rotation's last write is the one that records LastRotatedDate.
            storage.parkOn((key, secret) -> secret.getLastRotatedDate() != null);
            rotating.rotateSecret(SECRET_NAME, "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "arn:aws:lambda:us-east-1:000000000000:function:rotator", null, true, REGION, functionArn -> { });
            assertTrue(storage.awaitEntered(), "The rotation never reached its final save");

            Watched delete = start("delete", () -> rotating.deleteSecret(SECRET_NAME, null, true, REGION));
            assertTrue(delete.awaitBlocked(), "DeleteSecret did not wait for the rotation's final save");

            storage.release();
            delete.awaitSuccess();

            // Deleted after the final save, not before it, so the save cannot bring the secret back.
            assertThrows(AwsException.class, () -> rotating.describeSecret(SECRET_NAME, REGION));
        } finally {
            // Rotation workers are not daemon threads; leave none behind.
            rotating.shutdown();
        }
    }

    @Test
    void promotingAReplicaWaitsForAnInFlightSyncFromItsPrimary() throws Exception {
        service.replicateSecretToRegions(SECRET_NAME,
                List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION);

        // The primary's own write lands first; the copy pushed to the replica is what parks.
        storage.parkOn(REPLICA_REGION + "::" + SECRET_NAME);
        Watched writer = start("primary-writer", () ->
                service.putSecretValue(SECRET_NAME, "v2", null, null, REGION, null));
        assertTrue(storage.awaitEntered(), "PutSecretValue never reached the replica sync");

        Watched promoter = start("promoter", () ->
                service.stopReplicationToReplica(SECRET_NAME, REPLICA_REGION));
        assertTrue(promoter.awaitBlocked(),
                "StopReplicationToReplica did not wait for the in-flight replica sync");

        storage.release();
        writer.awaitSuccess();
        promoter.awaitSuccess();

        // The promotion ran after the sync, so it is the promotion that stands: the replica is a
        // standalone secret and the old primary no longer syncs into it.
        Secret promoted = service.describeSecret(SECRET_NAME, REPLICA_REGION);
        assertNull(promoted.getPrimaryRegion());
        assertTrue(service.describeSecret(SECRET_NAME, REGION).getReplicationStatus().isEmpty());
    }

    private static Watched start(String name, Runnable body) {
        Watched watched = new Watched(name, body);
        watched.thread.start();
        return watched;
    }

    /** Parks the first matching write so another thread can race the caller holding it. */
    private static final class ParkingStorage extends InMemoryStorage<String, Secret> {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile BiPredicate<String, Secret> parkWhen;

        void parkOn(String key) {
            parkOn((written, secret) -> written.equals(key));
        }

        void parkOn(BiPredicate<String, Secret> condition) {
            this.parkWhen = condition;
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void put(String key, Secret value) {
            BiPredicate<String, Secret> condition = parkWhen;
            if (condition != null && condition.test(key, value)) {
                parkWhen = null;
                entered.countDown();
                try {
                    released.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.put(key, value);
        }
    }

    /** A thread whose failure and whose wait on the secret's monitor the test can assert on. */
    private static final class Watched {

        private final Thread thread;
        private final AtomicReference<Throwable> thrown = new AtomicReference<>();

        Watched(String name, Runnable body) {
            this.thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, name);
            this.thread.setDaemon(true);
        }

        /** Waits for the thread to sit on a monitor rather than for a fixed interval to pass. */
        boolean awaitBlocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.BLOCKED) {
                    return true;
                }
                if (!thread.isAlive()) {
                    return false;
                }
                Thread.sleep(5);
            }
            return false;
        }

        void awaitSuccess() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!thread.isAlive(), thread.getName() + " did not finish within the join timeout");
            assertNull(thrown.get(), () -> thread.getName() + " failed: " + thrown.get());
        }
    }
}
