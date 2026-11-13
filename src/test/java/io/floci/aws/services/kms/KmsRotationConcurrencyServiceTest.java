package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsKey;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
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
            ThreadStates.awaitBlockedOrDone(rotation.thread());
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
            ThreadStates.awaitBlockedOrDone(deletion.thread());
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
            ThreadStates.awaitBlockedOrDone(rotation.thread());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnrelatedKeyRemainsUsableWhileAnotherKeyIsPublishing(boolean rotate) throws Exception {
        PausingKeys keys = new PausingKeys();
        KmsService service = service(keys);
        KmsKey blocked = service.createKey("blocked", REGION);
        KmsKey other = service.createKey("unrelated", REGION);
        while (Math.floorMod(blocked.getKeyId().hashCode(), 256)
                == Math.floorMod(other.getKeyId().hashCode(), 256)) {
            other = service.createKey("unrelated", REGION);
        }
        String otherId = other.getKeyId();
        keys.pauseWhen((name, value) -> name.equals(REGION + "::" + blocked.getKeyId()));
        Worker<Void> writer = start(() -> {
            if (rotate) {
                service.rotateKeyOnDemand(blocked.getKeyId(), REGION);
            } else {
                service.updateKeyDescription(blocked.getKeyId(), "after", REGION);
            }
            return null;
        });
        keys.awaitPaused();
        Worker<byte[]> independent = start(() -> {
            assertEquals(otherId, service.describeKey(otherId, REGION).getKeyId());
            service.updateKeyDescription(otherId, "still usable", REGION);
            byte[] encrypted = service.encrypt(otherId, PLAINTEXT, REGION);
            return service.decrypt(encrypted, REGION);
        });
        try {
            assertArrayEquals(PLAINTEXT, independent.future().get(2, TimeUnit.SECONDS));
        } finally {
            keys.resume();
            writer.result();
            independent.result();
        }
        assertEquals("still usable", service.describeKey(otherId, REGION).getDescription());
    }

    @Test
    void writersPreserveMalformedKeyArnErrors() {
        KmsService service = service(new PausingKeys());
        String arn = "arn:aws:kms:" + REGION + ":000000000000:key/invalid-id";
        AwsException rotation = assertThrows(AwsException.class, () -> service.rotateKeyOnDemand(arn, REGION));
        AwsException metadata = assertThrows(AwsException.class,
                () -> service.updateKeyDescription(arn, "updated", REGION));
        assertEquals("Invalid keyId invalid-id", rotation.getMessage());
        assertEquals(rotation.getMessage(), metadata.getMessage());
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
