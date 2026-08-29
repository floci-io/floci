package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies RAM organization settings and cross-account resource shares survive a restart. */
class RamServicePersistenceTest {

    private static final String OWNER = "111111111111";
    private static final String ACCEPTER = "222222222222";
    private static final String TGW_ARN =
            "arn:aws:ec2:us-east-1:111111111111:transit-gateway/tgw-0abc";

    @Test
    void resourceSharesAndOrganizationSettingSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        RamService first = serviceWithStorage(storage);
        first.enableSharingWithAwsOrganization();
        ResourceShare created = first.createResourceShare(
                "us-east-1-tgw-share",
                List.of("arn:aws:organizations::000000000000:ou/o-abc/ou-infra"),
                List.of(TGW_ARN), false, "us-east-1", OWNER);

        RamService reloaded = serviceWithStorage(storage);

        assertTrue(reloaded.isSharingWithOrganizationEnabled());
        List<ResourceShare> owned = reloaded.getResourceShares(OWNER, "SELF");
        assertEquals(1, owned.size());
        assertEquals(created.getResourceShareArn(), owned.getFirst().getResourceShareArn());
        assertEquals(created.getCreationTime(), owned.getFirst().getCreationTime());

        List<ResourceShare> shared = reloaded.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS");
        assertEquals(1, shared.size());
        List<SharedResource> resources = reloaded.listResources(
                ACCEPTER, "OTHER-ACCOUNTS", List.of(created.getResourceShareArn()));
        assertEquals(1, resources.size());
        assertEquals(TGW_ARN, resources.getFirst().arn());
    }

    private static RamService serviceWithStorage(StorageFactory storage) {
        RamService service = new RamService(storage);
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
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}

