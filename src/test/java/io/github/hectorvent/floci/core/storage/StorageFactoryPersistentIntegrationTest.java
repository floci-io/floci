package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(StorageFactoryPersistentIntegrationTest.PersistentStorageProfile.class)
class StorageFactoryPersistentIntegrationTest {

    @Inject
    StorageFactory storageFactory;

    @Test
    void ec2AndIamResolveToPersistentStorage() throws Exception {
        java.nio.file.Path basePath = java.nio.file.Path.of("/tmp/floci-persistent-tests");
        java.nio.file.Files.createDirectories(basePath);
        java.nio.file.Files.deleteIfExists(basePath.resolve("ec2-test.json"));
        java.nio.file.Files.deleteIfExists(basePath.resolve("iam-test.json"));

        StorageBackend<String, String> ec2Backend = storageFactory.create(
                "ec2",
                "ec2-test.json",
                new TypeReference<Map<String, String>>() {}
        );
        assertPersistent(ec2Backend);
        ec2Backend.put("my-key", "my-value");

        StorageBackend<String, String> iamBackend = storageFactory.create(
                "iam",
                "iam-test.json",
                new TypeReference<Map<String, String>>() {}
        );
        assertPersistent(iamBackend);
        iamBackend.put("my-key", "my-value");

        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(basePath.resolve("ec2-test.json")));
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(basePath.resolve("iam-test.json")));
    }

    private void assertPersistent(StorageBackend<String, ?> backend) throws Exception {
        assertInstanceOf(AccountAwareStorageBackend.class, backend);
        Field field = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        field.setAccessible(true);
        Object delegate = field.get(backend);
        assertNotNull(delegate);
        assertInstanceOf(PersistentStorage.class, delegate);
    }

    public static final class PersistentStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", "/tmp/floci-persistent-tests"
            );
        }
    }
}
