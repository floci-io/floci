package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void listResolverDnssecConfigs_returnsOnlyTheRegionsConfigs() {
        ObjectMapper objectMapper = new ObjectMapper();
        Route53ResolverService service = new Route53ResolverService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), objectMapper);
        String accountId = "000000000000";
        service.updateResolverDnssecConfig(objectMapper.createObjectNode()
                .put("ResourceId", "vpc-east").put("Validation", "ENABLE"), "us-east-1", accountId);
        service.updateResolverDnssecConfig(objectMapper.createObjectNode()
                .put("ResourceId", "vpc-west").put("Validation", "ENABLE"), "us-west-2", accountId);

        ObjectNode result = service.listResolverDnssecConfigs(null, null, null, "us-east-1");
        JsonNode east = result.path("ResolverDnssecConfigs");
        assertEquals(1, east.size());
        assertEquals("vpc-east", east.get(0).path("ResourceId").asText());
        assertEquals(accountId, east.get(0).path("OwnerId").asText());
        assertEquals("ENABLED", east.get(0).path("ValidationStatus").asText());
        assertTrue(east.get(0).path("Id").asText().startsWith("rdsc-"));
        assertTrue(service.listResolverDnssecConfigs(null, null, null, "eu-west-1")
                .path("ResolverDnssecConfigs").isEmpty());
    }

    @Test
    void listResolverDnssecConfigs_filtersByResourceId() {
        ObjectMapper objectMapper = new ObjectMapper();
        Route53ResolverService service = new Route53ResolverService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), objectMapper);
        String accountId = "000000000000";
        service.updateResolverDnssecConfig(objectMapper.createObjectNode()
                .put("ResourceId", "vpc-a").put("Validation", "ENABLE"), "us-east-1", accountId);
        service.updateResolverDnssecConfig(objectMapper.createObjectNode()
                .put("ResourceId", "vpc-b").put("Validation", "ENABLE"), "us-east-1", accountId);

        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("Name", "ResourceId");
        filter.set("Values", objectMapper.createArrayNode().add("vpc-b"));
        ObjectNode result = service.listResolverDnssecConfigs(
                objectMapper.createArrayNode().add(filter), null, null, "us-east-1");
        JsonNode matched = result.path("ResolverDnssecConfigs");
        assertEquals(1, matched.size());
        assertEquals("vpc-b", matched.get(0).path("ResourceId").asText());
        assertTrue(result.path("NextToken").isMissingNode());
    }

    @Test
    void listResolverDnssecConfigs_pagesByMaxResultsWithNextToken() {
        ObjectMapper objectMapper = new ObjectMapper();
        Route53ResolverService service = new Route53ResolverService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), objectMapper);
        String accountId = "000000000000";
        for (String vpc : List.of("vpc-a", "vpc-b", "vpc-c")) {
            service.updateResolverDnssecConfig(objectMapper.createObjectNode()
                    .put("ResourceId", vpc).put("Validation", "ENABLE"), "us-east-1", accountId);
        }

        ObjectNode firstPage = service.listResolverDnssecConfigs(null,
                objectMapper.getNodeFactory().numberNode(2), null, "us-east-1");
        assertEquals(2, firstPage.path("ResolverDnssecConfigs").size());
        String token = firstPage.path("NextToken").asText(null);
        assertTrue(token != null && !token.isBlank());

        ObjectNode secondPage = service.listResolverDnssecConfigs(null, null, token, "us-east-1");
        assertEquals(1, secondPage.path("ResolverDnssecConfigs").size());
        assertTrue(secondPage.path("NextToken").isMissingNode());

        assertThrows(AwsException.class, () -> service.listResolverDnssecConfigs(null,
                objectMapper.getNodeFactory().numberNode(101), null, "us-east-1"));
    }
}
