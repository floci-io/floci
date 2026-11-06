package io.floci.aws.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backendReleaseDoesNotBlockAnotherSecretOnTheSameStripe(boolean failBackend) throws Exception {
        Map<Integer, String> stripes = new HashMap<>();
        String primary = null;
        String unrelated = null;
        for (int index = 0; index <= 32; index++) {
            String arn = service.createSecret("collision-" + index, "v1", null, "original", null, null, REGION).getArn();
            String previous = stripes.putIfAbsent(Math.floorMod(arn.hashCode(), 32), arn);
            if (previous != null) {
                primary = previous;
                unrelated = arn;
                break;
            }
        }
        assertTrue(primary != null && unrelated != null);
        String releasingArn = primary;
        String otherArn = unrelated;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch unrelatedUpdated = new CountDownLatch(1);
        Watched deletion = start("managed-release", () -> {
            Runnable credentials = () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                if (failBackend) {
                    throw new IllegalStateException("backend failed");
                }
            };
            if (failBackend) {
                assertThrows(IllegalStateException.class,
                        () -> service.deleteManagedSecretAfter(releasingArn, REGION, credentials));
            } else {
                assertTrue(service.deleteManagedSecretAfter(releasingArn, REGION, credentials));
            }
        });
        Watched other = null;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            other = start("unrelated-update", () -> {
                service.updateSecret(otherArn, "independent", null, REGION);
                unrelatedUpdated.countDown();
            });
            assertTrue(unrelatedUpdated.await(2, TimeUnit.SECONDS), "Backend I/O blocked an unrelated secret");
        } finally {
            release.countDown();
            deletion.awaitSuccess();
            if (other != null) {
                other.awaitSuccess();
            }
        }
        assertEquals("independent", service.describeSecret(otherArn, REGION).getDescription());
        if (failBackend) {
            assertEquals("v1", service.getSecretValue(releasingArn, null, null, REGION).getSecretString());
            service.replicateSecretToRegions(releasingArn,
                    List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION);
            assertEquals(1, service.describeSecret(releasingArn, REGION).getReplicationStatus().size());
        } else {
            assertThrows(AwsException.class, () -> service.describeSecret(releasingArn, REGION));
        }
    }

    @Test
    void managedDeletionWaitsForReplicationAndPreservesCredentials() throws Exception {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        storage.parkOn(REPLICA_REGION + "::" + SECRET_NAME);
        Watched replication = start("replication", () -> service.replicateSecretToRegions(arn,
                List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION));
        AtomicReference<Boolean> changed = new AtomicReference<>(false);
        AtomicReference<Boolean> deleted = new AtomicReference<>();
        Watched deletion = null;
        try {
            assertTrue(storage.awaitEntered());
            deletion = start("managed-deletion", () -> deleted.set(
                    service.deleteManagedSecretAfter(arn, REGION, () -> changed.set(true))));
            assertTrue(deletion.awaitBlocked());
        } finally {
            storage.release();
            replication.awaitSuccess();
            if (deletion != null) {
                deletion.awaitSuccess();
            }
        }
        assertFalse(deleted.get());
        assertFalse(changed.get());
        assertEquals(1, service.describeSecret(arn, REGION).getReplicationStatus().size());
        assertEquals("v1", service.getSecretValue(SECRET_NAME, null, null, REPLICA_REGION).getSecretString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replicationChangesCannotResurrectAReleasedManagedSecret(boolean removeReplica) throws Exception {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        if (removeReplica) {
            service.replicateSecretToRegions(arn,
                    List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION);
            service.removeRegionsFromReplication(arn, List.of(REPLICA_REGION), REGION);
        }
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Watched deletion = start("managed-deletion", () -> assertTrue(
                service.deleteManagedSecretAfter(arn, REGION, () -> {
                    changing.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                })));
        Watched replication = null;
        try {
            assertTrue(changing.await(5, TimeUnit.SECONDS));
            replication = start("replication", () -> {
                AwsException missing = assertThrows(AwsException.class, () -> {
                    if (removeReplica) {
                        service.removeRegionsFromReplication(arn, List.of(REPLICA_REGION), REGION);
                    } else {
                        service.replicateSecretToRegions(arn,
                                List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION);
                    }
                });
                assertEquals("InvalidRequestException", missing.getErrorCode());
            });
            replication.awaitSuccess();
        } finally {
            release.countDown();
            deletion.awaitSuccess();
            if (replication != null) {
                replication.awaitSuccess();
            }
        }
        assertThrows(AwsException.class, () -> service.describeSecret(arn, REGION));
        assertThrows(AwsException.class, () -> service.describeSecret(SECRET_NAME, REPLICA_REGION));
    }

    @Test
    void queuedPromotionCannotRestoreSecretsAfterManagedRelease() throws Exception {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        service.replicateSecretToRegions(arn,
                List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), false, REGION);
        storage.parkOn(REGION + "::" + SECRET_NAME);
        storage.afterParkedWrite = () -> {
            service.removeRegionsFromReplication(arn, List.of(REPLICA_REGION), REGION);
            assertTrue(service.deleteManagedSecretAfter(arn, REGION, () -> { }));
        };
        Watched writer = start("primary-writer", () -> service.updateSecret(arn, "updated", null, REGION));
        Watched promotion = null;
        try {
            assertTrue(storage.awaitEntered());
            promotion = start("queued-promotion", () -> {
                AwsException missing = assertThrows(AwsException.class,
                        () -> service.stopReplicationToReplica(SECRET_NAME, REPLICA_REGION));
                assertEquals("ResourceNotFoundException", missing.getErrorCode());
            });
            assertTrue(promotion.awaitBlocked());
        } finally {
            storage.release();
            writer.awaitSuccess();
            if (promotion != null) {
                promotion.awaitSuccess();
            }
        }
        assertThrows(AwsException.class, () -> service.describeSecret(arn, REGION));
        assertThrows(AwsException.class, () -> service.describeSecret(SECRET_NAME, REPLICA_REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeReleaseRejectsDeletionAndDuplicateReleaseUntilBackendFailure(boolean forceDelete) throws Exception {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Watched owner = start("failing-release", () -> assertThrows(IllegalStateException.class,
                () -> service.deleteManagedSecretAfter(arn, REGION, () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    throw new IllegalStateException("backend failed");
                })));
        Watched concurrent = null;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            concurrent = start("concurrent-release", () -> {
                AwsException deletion = assertThrows(AwsException.class,
                        () -> service.deleteSecret(arn, null, forceDelete, REGION));
                assertEquals("InvalidRequestException", deletion.getErrorCode());
                AwsException duplicate = assertThrows(AwsException.class,
                        () -> service.deleteManagedSecretAfter(arn, REGION,
                                () -> { throw new AssertionError("Duplicate backend call"); }));
                assertEquals("InvalidRequestException", duplicate.getErrorCode());
            });
            concurrent.awaitSuccess();
        } finally {
            release.countDown();
            owner.awaitSuccess();
            if (concurrent != null) {
                concurrent.awaitSuccess();
            }
        }
        assertEquals("v1", service.getSecretValue(arn, null, null, REGION).getSecretString());
        assertTrue(service.deleteManagedSecretAfter(arn, REGION, () -> { }));
        assertThrows(AwsException.class, () -> service.describeSecret(arn, REGION));
    }

    @Test
    void failedCredentialChangeRetainsTheSecret() {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        assertThrows(IllegalStateException.class, () -> service.deleteManagedSecretAfter(arn, REGION, () -> {
            throw new IllegalStateException("backend refused rotation");
        }));
        assertEquals("v1", service.getSecretValue(arn, null, null, REGION).getSecretString());
    }

    @Test
    void alreadyRemovedManagedSecretStillAllowsCredentialChange() {
        String arn = service.describeSecret(SECRET_NAME, REGION).getArn();
        service.deleteSecret(arn, null, true, REGION);
        AtomicReference<Boolean> changed = new AtomicReference<>(false);
        assertTrue(service.deleteManagedSecretAfter(arn, REGION, () -> changed.set(true)));
        assertTrue(changed.get());
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
        when(lambda.getFunctionByArn(anyString())).thenReturn(new LambdaFunction());
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
        private Runnable afterParkedWrite;

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
            Runnable afterWrite = afterParkedWrite;
            if (afterWrite != null) {
                afterParkedWrite = null;
                afterWrite.run();
            }
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
