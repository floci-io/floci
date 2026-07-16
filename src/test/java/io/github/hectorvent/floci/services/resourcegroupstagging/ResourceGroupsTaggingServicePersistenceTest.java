package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies resource tag mappings survive a restart. Two service instances share the same
 * {@link StorageFactory} backend; the second simulates a process restart reloading from disk.
 */
class ResourceGroupsTaggingServicePersistenceTest {

    private static final String ARN = "arn:aws:s3:us-east-1:000000000000:bucket/persisted-bucket";

    @Test
    void tagMappingsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ResourceGroupsTaggingService first = serviceWithStorage(storage);
        first.tagResources(List.of(ARN), Map.of("Team", "platform", "Env", "dev"), "us-east-1");

        ResourceGroupsTaggingService reloaded = serviceWithStorage(storage);
        List<ResourceTagMapping> resources =
                reloaded.getResources(List.of(), List.of(), List.of(), null, 0, "us-east-1").items();
        assertEquals(1, resources.size());
        assertEquals(ARN, resources.getFirst().getResourceArn());
        assertEquals(Map.of("Team", "platform", "Env", "dev"), resources.getFirst().getTags());
    }

    @Test
    void untaggedKeysDoNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ResourceGroupsTaggingService first = serviceWithStorage(storage);
        first.tagResources(List.of(ARN), Map.of("Team", "platform", "Env", "dev"), "us-east-1");
        first.untagResources(List.of(ARN), List.of("Env"), "us-east-1");

        ResourceGroupsTaggingService reloaded = serviceWithStorage(storage);
        List<ResourceTagMapping> resources =
                reloaded.getResources(List.of(), List.of(), List.of(), null, 0, "us-east-1").items();
        assertEquals(1, resources.size());
        assertEquals(Map.of("Team", "platform"), resources.getFirst().getTags());
    }

    @Test
    void deletedResourcesDoNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ResourceGroupsTaggingService first = serviceWithStorage(storage);
        first.tagResources(List.of(ARN), Map.of("Team", "platform"), "us-east-1");
        first.deleteResources(List.of(ARN), "us-east-1");

        ResourceGroupsTaggingService reloaded = serviceWithStorage(storage);
        assertTrue(reloaded.getResources(List.of(), List.of(), List.of(), null, 0, "us-east-1")
                .items().isEmpty());
    }

    private static ResourceGroupsTaggingService serviceWithStorage(StorageFactory storage) {
        ResourceGroupsTaggingService service = new ResourceGroupsTaggingService(storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
