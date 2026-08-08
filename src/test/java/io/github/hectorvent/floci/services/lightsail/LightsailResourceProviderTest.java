package io.github.hectorvent.floci.services.lightsail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightsailResourceProviderTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private LightsailService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return new InMemoryStorage<>();
            }
        };
        service = new LightsailService(storageFactory, mapper, new RegionResolver(REGION, ACCOUNT));
    }

    @Nested
    class GetResources {

        @Test
        void emptyWhenNothingStored() {
            assertTrue(service.getResources().isEmpty());
        }

        @Test
        void surfacesInstanceWithArnTypeRegionAccountAndTags() {
            ObjectNode request = mapper.createObjectNode()
                    .put("availabilityZone", REGION + "a")
                    .put("blueprintId", "ubuntu_22_04")
                    .put("bundleId", "nano_3_0");
            request.set("instanceNames", mapper.createArrayNode().add("web"));
            request.set("tags", mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("key", "env").put("value", "prod")));
            service.createInstances(REGION, request);

            ExplorerResource instance = only(service.getResources(), "lightsail:Instance");
            assertEquals("arn:aws:lightsail:" + REGION + ":" + ACCOUNT + ":Instance/web", instance.arn());
            assertEquals("lightsail", instance.service());
            assertEquals(REGION, instance.region());
            assertEquals(ACCOUNT, instance.owningAccountId());
            assertEquals(Map.of("env", "prod"), instance.tags());
        }

        @Test
        void surfacesDiskInstanceStaticIpAndKeyPairTypes() {
            service.createInstances(REGION, mapper.createObjectNode()
                    .put("availabilityZone", REGION + "a")
                    .put("blueprintId", "ubuntu_22_04")
                    .put("bundleId", "nano_3_0")
                    .set("instanceNames", mapper.createArrayNode().add("web")));
            service.createDisk(REGION, mapper.createObjectNode()
                    .put("diskName", "data")
                    .put("availabilityZone", REGION + "a")
                    .put("sizeInGb", 32));
            service.allocateStaticIp(REGION, "ip1");
            service.createKeyPair(REGION, mapper.createObjectNode().put("keyPairName", "kp1"));

            Set<String> types = service.getResources().stream()
                    .map(ExplorerResource::resourceType)
                    .collect(Collectors.toSet());
            assertEquals(Set.of("lightsail:Instance", "lightsail:Disk",
                    "lightsail:StaticIp", "lightsail:KeyPair"), types);
        }

        @Test
        void resourceWithoutTagsYieldsEmptyMapNotNull() {
            service.allocateStaticIp(REGION, "ip1");
            ExplorerResource ip = only(service.getResources(), "lightsail:StaticIp");
            assertEquals(Map.of(), ip.tags());
        }
    }

    @Nested
    class GetSupportedResourceTypes {

        @Test
        void advertisesFourLightsailTypesAllUnderLightsailService() {
            Set<SupportedResourceType> types = service.getSupportedResourceTypes();
            assertEquals(Set.of("lightsail:Instance", "lightsail:Disk",
                            "lightsail:StaticIp", "lightsail:KeyPair"),
                    types.stream().map(SupportedResourceType::resourceType).collect(Collectors.toSet()));
            assertTrue(types.stream().allMatch(t -> t.service().equals("lightsail")));
        }
    }

    private static ExplorerResource only(List<ExplorerResource> resources, String resourceType) {
        return resources.stream()
                .filter(r -> r.resourceType().equals(resourceType))
                .reduce((a, b) -> { throw new AssertionError("expected exactly one " + resourceType); })
                .orElseThrow(() -> new AssertionError("no " + resourceType + " resource"));
    }
}
