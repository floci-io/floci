package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Route53ResolverDnssecStorageTest {

    @Test
    void failedUpdatePreservesStoredValidationStatus() {
        ObjectMapper objectMapper = new ObjectMapper();
        InMemoryStorage<String, ObjectNode> dnssecConfigs = new InMemoryStorage<>() {
            @Override
            public synchronized void put(String key, ObjectNode value) {
                if ("DISABLED".equals(value.path("ValidationStatus").asText())) {
                    throw new IllegalStateException("simulated storage write failure");
                }
                super.put(key, value);
            }
        };
        Route53ResolverService service = new Route53ResolverService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                dnssecConfigs, new InMemoryStorage<>(), new InMemoryStorage<>(), objectMapper);
        String region = "us-east-1";
        String accountId = "000000000000";
        String vpcId = "vpc-dnssec-storage-test";
        ObjectNode request = objectMapper.createObjectNode().put("ResourceId", vpcId)
                .put("Validation", "ENABLE");
        ObjectNode original = service.updateResolverDnssecConfig(request, region, accountId);

        request.put("Validation", "DISABLE");
        assertThrows(IllegalStateException.class,
                () -> service.updateResolverDnssecConfig(request, region, accountId));

        ObjectNode stored = dnssecConfigs.get(region + "::" + vpcId).orElseThrow();
        assertEquals("ENABLED", stored.path("ValidationStatus").asText());
        assertEquals(original.path("Id").asText(), stored.path("Id").asText());
        assertEquals("ENABLED", service.getResolverDnssecConfig(vpcId, region)
                .path("ValidationStatus").asText());
    }
}
