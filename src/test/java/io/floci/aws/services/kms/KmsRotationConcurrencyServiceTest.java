package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class KmsRotationConcurrencyServiceTest {
    private static final String REGION = "us-east-1";
    private static final String REPLICA_REGION = "us-west-2";
    private static final byte[] PLAINTEXT = "concurrent rotation".getBytes(StandardCharsets.UTF_8);

    @Test
    void aMetadataWriterCannotRepublishMaterialFromBeforeRotation() throws Exception {
        PausingKeys keys = new PausingKeys();
        KmsService service = service(keys);
        KmsKey key = service.createKey("before", REGION);
        keys.pauseWhen((name, value) -> "after".equals(value.getDescription()));
        Worker<Void> metadata = start(() -> {
            service.updateKeyDescription(key.getKeyId(), "after", REGION);
            return null;
        });
        keys.awaitPaused();
        Worker<byte[]> rotation = start(() -> {
            service.rotateKeyOnDemand(key.getKeyId(), REGION);
            return service.encrypt(key.getKeyId(), PLAINTEXT, REGION);
        });
        try {
            rotation.awaitBlockedOrFinished(metadata.thread());
        } finally {
            keys.resume();
        }
        metadata.result();
        byte[] ciphertext = rotation.result();

        KmsKey current = service.describeKey(key.getKeyId(), REGION);
        assertEquals("after", current.getDescription());
        assertEquals(1, current.getOnDemandRotationCount());
        assertEquals(2, current.getBackingKeys().size());
        assertEquals(1, service.listKeyRotations(key.getKeyId(), null, REGION, null, null).items().size());
        assertArrayEquals(PLAINTEXT, service.decrypt(ciphertext, REGION));
    }

    @Test
    void rotationPublicationCannotEraseAConcurrentDeletionSchedule() throws Exception {
        PausingKeys keys = new PausingKeys();
        KmsService service = service(keys);
        KmsKey key = service.createKey("pending deletion", REGION);
        keys.pauseWhen((name, value) -> value.getOnDemandRotationCount() == 1);
        Worker<String> rotation = start(() -> service.rotateKeyOnDemand(key.getKeyId(), REGION));
        keys.awaitPaused();
        Worker<Void> deletion = start(() -> {
            service.scheduleKeyDeletion(key.getKeyId(), 7, REGION);
            return null;
        });
        try {
            deletion.awaitBlockedOrFinished(rotation.thread());
        } finally {
            keys.resume();
        }
        rotation.result();
        deletion.result();

        KmsKey current = service.describeKey(key.getKeyId(), REGION);
        assertEquals("PendingDeletion", current.getKeyState());
        assertTrue(current.getDeletionDate() > 0);
        assertEquals(1, current.getOnDemandRotationCount());
        assertEquals(2, current.getBackingKeys().size());
        assertEquals(1, service.listKeyRotations(key.getKeyId(), null, REGION, null, null).items().size());
    }

    @Test
    void aReplicaPublishedDuringRotationReceivesTheNewMaterialAndHistory() throws Exception {
        PausingKeys keys = new PausingKeys();
        KmsService service = service(keys);
        KmsKey primary = service.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        keys.pauseWhen((name, value) -> name.startsWith(REPLICA_REGION + "::"));
        Worker<KmsKey> replication = start(() -> service.replicateKey(
                primary.getKeyId(), "replica", null, Map.of(), REPLICA_REGION, REGION));
        keys.awaitPaused();
        Worker<byte[]> rotation = start(() -> {
            service.rotateKeyOnDemand(primary.getKeyId(), REGION);
            return service.encrypt(primary.getKeyId(), PLAINTEXT, REGION);
        });
        try {
            rotation.awaitBlockedOrFinished(replication.thread());
        } finally {
            keys.resume();
        }
        replication.result();
        byte[] ciphertext = rotation.result();

        KmsKey current = service.describeKey(primary.getKeyId(), REGION);
        KmsKey replica = service.describeKey(primary.getKeyId(), REPLICA_REGION);
        assertEquals(current.getBackingKeys(), replica.getBackingKeys());
        assertEquals(current.getCurrentBackingKeyId(), replica.getCurrentBackingKeyId());
        assertEquals(service.listKeyRotations(primary.getKeyId(), null, REGION, null, null).items(),
                service.listKeyRotations(primary.getKeyId(), null, REPLICA_REGION, null, null).items());
        assertArrayEquals(PLAINTEXT, service.decrypt(ciphertext, REPLICA_REGION));
    }

    private static KmsService service(PausingKeys keys) {
        return new KmsService(keys, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
    }

    private static <T> Worker<T> start(Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                result.complete(operation.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return new Worker<>(thread, result);
    }

    private record Worker<T>(Thread thread, CompletableFuture<T> future) {
        /**
         * The fixed writer blocks on the key lock. The old implementation instead finishes
         * while the first put is paused, forcing the lost-update interleaving. Both paths
         * release the storage gate before assertions, so this tests persisted behavior.
         */
        private void awaitBlockedOrFinished(Thread owner) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!future.isDone()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("publication wait interrupted");
                    }
                    ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
                    if (info != null && info.getLockOwnerId() == owner.threadId()) {
                        return;
                    }
                    Thread.onSpinWait();
                }
            });
        }

        private T result() throws Exception {
            return future.get(5, TimeUnit.SECONDS);
        }
    }

    private static final class PausingKeys extends InMemoryStorage<String, KmsKey> {
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean claimed = new AtomicBoolean();
        private BiPredicate<String, KmsKey> pause;

        private void pauseWhen(BiPredicate<String, KmsKey> condition) {
            pause = condition;
        }

        private void awaitPaused() throws InterruptedException {
            assertTrue(paused.await(5, TimeUnit.SECONDS), "writer did not reach its publication");
        }

        private void resume() {
            resume.countDown();
        }

        @Override
        public void put(String name, KmsKey key) {
            pausePublication(name, key);
            super.put(name, key);
        }

        @Override
        public void putAllAtomically(Map<String, KmsKey> entries) {
            entries.forEach(this::pausePublication);
            super.putAllAtomically(entries);
        }

        private void pausePublication(String name, KmsKey key) {
            if (pause != null && pause.test(name, key) && claimed.compareAndSet(false, true)) {
                paused.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release paused publication");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("paused publication interrupted", error);
                }
            }
        }
    }
}
