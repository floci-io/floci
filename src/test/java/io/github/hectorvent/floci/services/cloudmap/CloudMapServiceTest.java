package io.github.hectorvent.floci.services.cloudmap;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudMapServiceTest {

    private static final String REGION = "us-east-1";
    private static final String A_RECORD_DNS_CONFIG =
            "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}";

    @Inject
    CloudMapService cloudMapService;

    @Test
    void updateServiceChangesTheTtlOfTheExistingRecords() {
        Service service = createService(privateDnsNamespace(), "ttl", "original", A_RECORD_DNS_CONFIG);

        cloudMapService.updateService(service.getId(), "original",
                "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":30}]}", null);

        assertTrue(cloudMapService.getService(service.getId()).getDnsConfig().contains("\"TTL\":30"));
    }

    @Test
    void updateServiceAcceptsTheSameRecordsInAnotherOrder() {
        Service service = createService(privateDnsNamespace(), "order", "original",
                "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60},"
                        + "{\"Type\":\"AAAA\",\"TTL\":60}]}");

        cloudMapService.updateService(service.getId(), "original",
                "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"AAAA\",\"TTL\":30},"
                        + "{\"Type\":\"A\",\"TTL\":30}]}", null);

        String stored = cloudMapService.getService(service.getId()).getDnsConfig();
        assertTrue(stored.contains("\"TTL\":30"));
        assertFalse(stored.contains("\"TTL\":60"));
    }

    @Test
    void updateServiceRejectsAChangedRecordType() {
        Service service = createService(privateDnsNamespace(), "type", "original", A_RECORD_DNS_CONFIG);

        AwsException error = assertThrows(AwsException.class, () -> cloudMapService.updateService(
                service.getId(), "changed",
                "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"AAAA\",\"TTL\":60}]}", null));

        assertEquals("InvalidInput", error.getErrorCode());
        Service stored = cloudMapService.getService(service.getId());
        assertEquals(A_RECORD_DNS_CONFIG, stored.getDnsConfig());
        assertEquals("original", stored.getDescription());
    }

    @Test
    void updateServiceRejectsAChangedRoutingPolicy() {
        Service service = createService(privateDnsNamespace(), "routing", "original", A_RECORD_DNS_CONFIG);

        AwsException error = assertThrows(AwsException.class, () -> cloudMapService.updateService(
                service.getId(), "original",
                "{\"RoutingPolicy\":\"WEIGHTED\",\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}", null));

        assertEquals("InvalidInput", error.getErrorCode());
        assertEquals(A_RECORD_DNS_CONFIG, cloudMapService.getService(service.getId()).getDnsConfig());
    }

    @Test
    void updateServiceRejectsAnAddedRecord() {
        Service service = createService(privateDnsNamespace(), "added", "original", A_RECORD_DNS_CONFIG);

        AwsException error = assertThrows(AwsException.class, () -> cloudMapService.updateService(
                service.getId(), "original",
                "{\"RoutingPolicy\":\"MULTIVALUE\",\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60},"
                        + "{\"Type\":\"A\",\"TTL\":60}]}", null));

        assertEquals("InvalidInput", error.getErrorCode());
        assertEquals(A_RECORD_DNS_CONFIG, cloudMapService.getService(service.getId()).getDnsConfig());
    }

    @Test
    void updateServiceRejectsADnsConfigForAServiceWithoutOne() {
        Service service = createService(privateDnsNamespace(), "nodns", null, null);

        AwsException error = assertThrows(AwsException.class, () -> cloudMapService.updateService(
                service.getId(), null, "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}", null));

        assertEquals("InvalidInput", error.getErrorCode());
        assertNull(cloudMapService.getService(service.getId()).getDnsConfig());
    }

    @Test
    void filterInstancesKeepsOnlyInstancesWithEveryRequestedAttribute() {
        Service service = createService(privateDnsNamespace(), "attrs", null, null);
        register(service, "prod", Map.of("AWS_INSTANCE_IPV4", "10.0.0.1", "stage", "prod", "tier", "web"));
        register(service, "dev", Map.of("AWS_INSTANCE_IPV4", "10.0.0.2", "stage", "dev", "tier", "web"));

        assertEquals(List.of("prod"), instanceIds(cloudMapService.filterInstances(
                service.getId(), "ALL", Map.of("stage", "prod", "tier", "web"))));
        assertEquals(List.of(), cloudMapService.filterInstances(service.getId(), "ALL", Map.of("stage", "qa")));
        assertEquals(2, cloudMapService.filterInstances(service.getId(), "ALL", Map.of()).size());
        assertEquals(2, cloudMapService.filterInstances(service.getId(), null, null).size());
    }

    @Test
    void filterInstancesAppliesTheHealthFilter() {
        Service service = createService(privateDnsNamespace(), "health", null, null);
        register(service, "up", Map.of("AWS_INSTANCE_IPV4", "10.0.0.1"));
        register(service, "down", Map.of("AWS_INSTANCE_IPV4", "10.0.0.2"));
        cloudMapService.getInstance(service.getId(), "down").setHealthStatus("UNHEALTHY");

        assertEquals(List.of("up"), instanceIds(cloudMapService.filterInstances(service.getId(), "HEALTHY", Map.of())));
        assertEquals(List.of("down"), instanceIds(cloudMapService.filterInstances(service.getId(), "UNHEALTHY", Map.of())));
        assertEquals(2, cloudMapService.filterInstances(service.getId(), "ALL", Map.of()).size());
    }

    @Test
    void filterInstancesRejectsAnUnknownService() {
        AwsException error = assertThrows(AwsException.class,
                () -> cloudMapService.filterInstances("srv-missing", "ALL", Map.of()));

        assertEquals("ServiceNotFound", error.getErrorCode());
    }

    private void register(Service service, String instanceId, Map<String, String> attributes) {
        cloudMapService.registerInstance(service.getId(), instanceId, null, attributes, REGION);
    }

    private static List<String> instanceIds(List<Instance> instances) {
        return instances.stream().map(Instance::getInstanceId).sorted().toList();
    }

    private String privateDnsNamespace() {
        String name = "svctest" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
        return cloudMapService.createPrivateDnsNamespace(name, "vpc-dns", null, null, Map.of(), REGION)
                .getTargets().get("NAMESPACE");
    }

    private Service createService(String namespaceId, String name, String description, String dnsConfig) {
        return cloudMapService.createService(name, namespaceId, null, description,
                dnsConfig, null, null, null, Map.of(), REGION);
    }
}
