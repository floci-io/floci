package io.floci.aws.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KmsRotationPublicationServiceTest {
    @TempDir
    Path directory;

    private static class Keys extends InMemoryStorage<String, KmsKey> {
        private boolean reject;
        private final IllegalStateException fault = new IllegalStateException("synthetic rejected write");

        @Override
        public void put(String name, KmsKey key) {
            if (reject) {
                throw fault;
            }
            super.put(name, key);
        }
    }

    @Test
    void rejectedWritePreservesSnapshotAndSuccessfulRetryPublishesOnce() throws Exception {
        Keys keys = new Keys();
        KmsService service = new KmsService(keys, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        KmsKey key = service.createKey("synthetic publication regression", "us-east-1");
        ObjectMapper mapper = new ObjectMapper();
        byte[] plaintext = "before rejected rotation".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = service.encrypt(key.getKeyId(), plaintext, "us-east-1");
        String location = "us-east-1::" + key.getKeyId();
        ObjectNode before = mapper.valueToTree(keys.get(location).orElseThrow());
        keys.reject = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rotateKeyOnDemand(key.getKeyId(), "us-east-1"));

        assertSame(keys.fault, failure);
        assertEquals(before, mapper.valueToTree(keys.get(location).orElseThrow()));
        assertTrue(service.listKeyRotations(key.getKeyId(), null, "us-east-1", null, null).items().isEmpty());
        assertArrayEquals(plaintext, service.decrypt(ciphertext, "us-east-1"));
        keys.reject = false;

        service.rotateKeyOnDemand(key.getKeyId(), "us-east-1");

        KmsKey rotated = keys.get(location).orElseThrow();
        assertNotSame(key, rotated);
        assertEquals(1, rotated.getOnDemandRotationCount());
        assertEquals(2, rotated.getBackingKeys().size());
        assertEquals(1, service.listKeyRotations(key.getKeyId(), null, "us-east-1", null, null).items().size());
        assertArrayEquals(plaintext, service.decrypt(ciphertext, "us-east-1"));
        ObjectNode after = mapper.valueToTree(rotated);
        for (String field : List.of("onDemandRotationCount", "backingKeys", "currentBackingKeyId",
                "backingKeyRotationDates")) {
            before.remove(field);
            after.remove(field);
        }
        assertEquals(before, after);
    }

    @Test
    void rejectedReplicaWritePreservesBothSnapshotsAndRetryPublishesOnce() throws Exception {
        String primaryRegion = "us-east-1";
        String replicaRegion = "us-west-2";
        IllegalStateException fault = new IllegalStateException("synthetic replica rejection");
        boolean[] reject = {false};
        InMemoryStorage<String, KmsKey> keys = new InMemoryStorage<>() {
            @Override
            public void put(String name, KmsKey key) {
                if (reject[0] && name.startsWith(replicaRegion + "::")) {
                    throw fault;
                }
                super.put(name, key);
            }

            @Override
            public void putAllAtomically(Map<String, KmsKey> entries) {
                if (reject[0] && entries.keySet().stream()
                        .anyMatch(name -> name.startsWith(replicaRegion + "::"))) {
                    throw fault;
                }
                super.putAllAtomically(entries);
            }
        };
        KmsService service = new KmsService(keys, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(primaryRegion, "000000000000"));
        KmsKey primary = service.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, primaryRegion);
        service.replicateKey(primary.getKeyId(), "replica", null, Map.of(), replicaRegion, primaryRegion);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode primaryBefore = mapper.valueToTree(service.describeKey(primary.getKeyId(), primaryRegion));
        ObjectNode replicaBefore = mapper.valueToTree(service.describeKey(primary.getKeyId(), replicaRegion));
        byte[] plaintext = "replica publication".getBytes(StandardCharsets.UTF_8);
        byte[] oldCiphertext = service.encrypt(primary.getKeyId(), plaintext, primaryRegion);
        reject[0] = true;

        assertSame(fault, assertThrows(IllegalStateException.class,
                () -> service.rotateKeyOnDemand(primary.getKeyId(), primaryRegion)));

        assertEquals(primaryBefore, mapper.valueToTree(service.describeKey(primary.getKeyId(), primaryRegion)));
        assertEquals(replicaBefore, mapper.valueToTree(service.describeKey(primary.getKeyId(), replicaRegion)));
        assertEquals(0, service.describeKey(primary.getKeyId(), primaryRegion).getOnDemandRotationCount());
        for (String region : List.of(primaryRegion, replicaRegion)) {
            assertTrue(service.listKeyRotations(primary.getKeyId(), null, region, null, null).items().isEmpty());
            assertArrayEquals(plaintext, service.decrypt(oldCiphertext, region));
        }
        reject[0] = false;

        service.rotateKeyOnDemand(primary.getKeyId(), primaryRegion);

        byte[] newCiphertext = service.encrypt(primary.getKeyId(), plaintext, primaryRegion);
        assertEquals(1, service.describeKey(primary.getKeyId(), primaryRegion).getOnDemandRotationCount());
        for (String region : List.of(primaryRegion, replicaRegion)) {
            assertEquals(1, service.listKeyRotations(primary.getKeyId(), null, region, null, null).items().size());
            assertArrayEquals(plaintext, service.decrypt(oldCiphertext, region));
            assertArrayEquals(plaintext, service.decrypt(newCiphertext, region));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void acceptedReplicaRotationSurvivesStorageRestart(String mode) {
        StorageBackend<String, KmsKey> keys = persistentKeys(mode);
        keys.load();
        String primaryRegion = "us-east-1";
        String replicaRegion = "us-west-2";
        String keyId;
        byte[] plaintext = "persistent rotation".getBytes(StandardCharsets.UTF_8);
        byte[] oldCiphertext;
        byte[] newCiphertext;
        try {
            KmsService service = service(keys, primaryRegion);
            KmsKey primary = service.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                    null, Map.of(), null, true, primaryRegion);
            keyId = primary.getKeyId();
            service.replicateKey(keyId, "replica", null, Map.of(), replicaRegion, primaryRegion);
            oldCiphertext = service.encrypt(keyId, plaintext, primaryRegion);
            service.rotateKeyOnDemand(keyId, primaryRegion);
            newCiphertext = service.encrypt(keyId, plaintext, primaryRegion);
            keys.flush();
        } finally {
            close(keys);
        }

        StorageBackend<String, KmsKey> reopened = persistentKeys(mode);
        reopened.load();
        try {
            KmsService service = service(reopened, primaryRegion);
            assertEquals(1, service.describeKey(keyId, primaryRegion).getOnDemandRotationCount());
            for (String region : List.of(primaryRegion, replicaRegion)) {
                assertEquals(1, service.listKeyRotations(keyId, null, region, null, null).items().size());
                assertArrayEquals(plaintext, service.decrypt(oldCiphertext, region));
                assertArrayEquals(plaintext, service.decrypt(newCiphertext, region));
            }
            assertEquals(service.describeKey(keyId, primaryRegion).getBackingKeys(),
                    service.describeKey(keyId, replicaRegion).getBackingKeys());
        } finally {
            close(reopened);
        }
    }

    private StorageBackend<String, KmsKey> persistentKeys(String mode) {
        TypeReference<Map<String, KmsKey>> type = new TypeReference<>() {};
        Path snapshot = directory.resolve("keys.json");
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, Long.MAX_VALUE);
            case "wal" -> new WalStorage<>(snapshot, directory.resolve("keys.wal"), type, Long.MAX_VALUE);
            default -> throw new IllegalArgumentException("Unsupported test storage mode: " + mode);
        };
    }

    private static KmsService service(StorageBackend<String, KmsKey> keys, String region) {
        return new KmsService(keys, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(region, "000000000000"));
    }

    private static void close(StorageBackend<String, KmsKey> keys) {
        if (keys instanceof HybridStorage<String, KmsKey> hybrid) {
            hybrid.shutdown();
        } else if (keys instanceof WalStorage<String, KmsKey> wal) {
            wal.shutdown();
        }
    }
}
