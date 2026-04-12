package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@QuarkusTest
@TestProfile(StorageFactoryServiceCatalogIntegrationTest.AcmPersistentStorageProfile.class)
class StorageFactoryServiceCatalogIntegrationTest {

    @Inject
    StorageFactory storageFactory;

    @Test
    void acmStorageOverrideIsApplied() {
        StorageBackend<String, String> backend = storageFactory.create(
                "acm",
                "acm-test.json",
                new TypeReference<Map<String, String>>() {}
        );

        assertInstanceOf(PersistentStorage.class, backend);
    }

    public static final class AcmPersistentStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "memory",
                    "floci.storage.services.acm.mode", "persistent",
                    "floci.storage.persistent-path", "/tmp/floci-service-registry-unification-tests"
            );
        }
    }
}
