package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53ResolverDnssecStorageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT_ID = "000000000000";

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
        Route53ResolverService service = service();
        validate(service, "vpc-east", "ENABLE", "us-east-1");
        validate(service, "vpc-west", "ENABLE", "us-west-2");

        ObjectNode result = service.listResolverDnssecConfigs(null, null, null, "us-east-1");
        JsonNode east = result.path("ResolverDnssecConfigs");
        assertEquals(1, east.size());
        assertEquals("vpc-east", east.get(0).path("ResourceId").asText());
        assertEquals(ACCOUNT_ID, east.get(0).path("OwnerId").asText());
        assertEquals("ENABLED", east.get(0).path("ValidationStatus").asText());
        assertTrue(east.get(0).path("Id").asText().startsWith("rdsc-"));
        assertTrue(service.listResolverDnssecConfigs(null, null, null, "eu-west-1")
                .path("ResolverDnssecConfigs").isEmpty());
    }

    @Test
    void listResolverDnssecConfigs_filtersByResourceId() {
        Route53ResolverService service = service();
        validate(service, "vpc-a", "ENABLE", "us-east-1");
        validate(service, "vpc-b", "ENABLE", "us-east-1");

        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("Name", "ResourceId");
        filter.set("Values", MAPPER.createArrayNode().add("vpc-b"));
        ObjectNode result = service.listResolverDnssecConfigs(
                MAPPER.createArrayNode().add(filter), null, null, "us-east-1");
        JsonNode matched = result.path("ResolverDnssecConfigs");
        assertEquals(1, matched.size());
        assertEquals("vpc-b", matched.get(0).path("ResourceId").asText());
        assertTrue(result.path("NextToken").isMissingNode());
    }

    @Test
    void listResolverDnssecConfigs_pagesByMaxResultsWithNextToken() {
        Route53ResolverService service = service();
        for (String vpc : List.of("vpc-a", "vpc-b", "vpc-c")) {
            validate(service, vpc, "ENABLE", "us-east-1");
        }

        ObjectNode firstPage = service.listResolverDnssecConfigs(null,
                MAPPER.getNodeFactory().numberNode(2), null, "us-east-1");
        assertEquals(2, firstPage.path("ResolverDnssecConfigs").size());
        String token = firstPage.path("NextToken").asText(null);
        assertTrue(token != null && !token.isBlank());

        ObjectNode secondPage = service.listResolverDnssecConfigs(null, null, token, "us-east-1");
        assertEquals(1, secondPage.path("ResolverDnssecConfigs").size());
        assertTrue(secondPage.path("NextToken").isMissingNode());

        assertThrows(AwsException.class, () -> service.listResolverDnssecConfigs(null,
                MAPPER.getNodeFactory().numberNode(101), null, "us-east-1"));
    }

    // The model: the response "doesn't contain disabled DNSSEC configurations". A disabled config still answers Get.
    @Test
    void listResolverDnssecConfigs_leavesOutDisabledConfigs() {
        Route53ResolverService service = service();
        validate(service, "vpc-on", "ENABLE", "us-east-1");
        validate(service, "vpc-local", "USE_LOCAL_RESOURCE_SETTING", "us-east-1");
        validate(service, "vpc-off", "ENABLE", "us-east-1");
        validate(service, "vpc-off", "DISABLE", "us-east-1");
        validate(service, "vpc-never", "DISABLE", "us-east-1");

        ObjectNode result = service.listResolverDnssecConfigs(null,
                MAPPER.getNodeFactory().numberNode(2), null, "us-east-1");
        assertEquals(Set.of("vpc-on", "vpc-local"), resourceIds(result.path("ResolverDnssecConfigs")));
        assertTrue(result.path("NextToken").isMissingNode(), "a disabled config takes no place on a page");
        assertEquals("DISABLED", service.getResolverDnssecConfig("vpc-off", "us-east-1")
                .path("ValidationStatus").asText());
    }

    private static Route53ResolverService service() {
        return new Route53ResolverService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                MAPPER);
    }

    private static void validate(Route53ResolverService service, String vpc, String validation, String region) {
        service.updateResolverDnssecConfig(MAPPER.createObjectNode().put("ResourceId", vpc)
                .put("Validation", validation), region, ACCOUNT_ID);
    }

    private static Set<String> resourceIds(JsonNode configs) {
        Set<String> ids = new HashSet<>();
        configs.forEach(config -> ids.add(config.path("ResourceId").asText()));
        return ids;
    }
}
