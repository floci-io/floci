package io.github.hectorvent.floci.services.cloudmap;

import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud Map's control plane always accepted namespaces, services and instances, but nothing
 * answered DNS for {@code <service>.<namespace>}, so a caller that registered successfully
 * still could not resolve its peers. These cover the lookup the embedded DNS server runs.
 */
@QuarkusTest
class CloudMapDnsResolutionTest {

    private static final String REGION = "us-east-1";

    @Inject
    CloudMapService cloudMapService;

    @Test
    void resolvesARegisteredInstanceInAPrivateDnsNamespace() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "valkey");
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(List.of("172.31.0.6"), cloudMapService.resolveDnsName("valkey." + namespace));
    }

    @Test
    void resolvesEveryHealthyInstanceOfAService() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "api");
        registerInstance(service.getId(), "task-1", "172.31.0.6");
        registerInstance(service.getId(), "task-2", "172.31.0.7");

        assertEquals(2, cloudMapService.resolveDnsName("api." + namespace).size());
        assertTrue(cloudMapService.resolveDnsName("api." + namespace).contains("172.31.0.7"));
    }

    @Test
    void answersWithAtMostEightRecords() {
        // Route 53 answers a service discovery query with up to eight records, however many
        // instances are registered behind the name.
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "fleet");
        for (int i = 1; i <= 11; i++) {
            registerInstance(service.getId(), "task-" + i, "172.31.0." + i);
        }

        assertEquals(8, cloudMapService.resolveDnsName("fleet." + namespace).size());
    }

    @Test
    void resolutionIsCaseInsensitiveAndToleratesATrailingDot() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "valkey");
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(List.of("172.31.0.6"),
                cloudMapService.resolveDnsName("VALKEY." + namespace.toUpperCase() + "."));
    }

    @Test
    void resolvesAPublicDnsNamespaceToo() {
        String namespace = uniqueNamespace();
        Operation operation = cloudMapService.createPublicDnsNamespace(
                namespace, null, null, Map.of(), REGION);
        Service service = createService(operation.getTargets().get("NAMESPACE"), "edge");
        registerInstance(service.getId(), "task-1", "172.31.0.9");

        assertEquals(List.of("172.31.0.9"), cloudMapService.resolveDnsName("edge." + namespace));
    }

    @Test
    void ignoresAnInstanceWhoseAddressIsNotIpv4() {
        // AWS rejects a non-IPv4 AWS_INSTANCE_IPV4; Floci stores it, and a value the DNS
        // server cannot put in an A record must not take the answer down with it.
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "mixed");
        registerInstance(service.getId(), "task-bad", "not-an-address");
        registerInstance(service.getId(), "task-good", "172.31.0.12");

        assertEquals(List.of("172.31.0.12"), cloudMapService.resolveDnsName("mixed." + namespace));
    }

    @Test
    void doesNotResolveAnHttpNamespace() {
        // An HTTP namespace is reachable through DiscoverInstances and has no DNS records on AWS.
        String namespace = uniqueNamespace();
        Operation operation = cloudMapService.createHttpNamespace(namespace, null, null, Map.of(), REGION);
        Service service = createService(operation.getTargets().get("NAMESPACE"), "internal");
        registerInstance(service.getId(), "task-1", "172.31.0.8");

        assertTrue(cloudMapService.resolveDnsName("internal." + namespace).isEmpty());
    }

    @Test
    void doesNotResolveAServiceWithNoRegisteredInstance() {
        String namespace = uniqueNamespace();
        createService(privateDnsNamespace(namespace), "empty");

        assertTrue(cloudMapService.resolveDnsName("empty." + namespace).isEmpty());
        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned("empty." + namespace).orElseThrow());
    }

    @Test
    void doesNotResolveANameOutsideAnyNamespace() {
        assertTrue(cloudMapService.resolveDnsName("example.com").isEmpty());
        assertTrue(cloudMapService.resolveDnsNameIfOwned("example.com").isEmpty());
        assertTrue(cloudMapService.resolveDnsName("").isEmpty());
        assertTrue(cloudMapService.resolveDnsName(null).isEmpty());
    }

    @Test
    void doesNotResolveTheNamespaceNameOnItsOwn() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "valkey");
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertTrue(cloudMapService.resolveDnsName(namespace).isEmpty());
        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned(namespace).orElseThrow());
    }

    @Test
    void nestedNamespaceApexDoesNotResolveThroughTheParentNamespace() {
        String parentName = uniqueNamespace();
        String childName = "nested." + parentName;
        String parentId = privateDnsNamespace(parentName);
        privateDnsNamespace(childName);
        Service parentService = createService(parentId, "nested");
        registerInstance(parentService.getId(), "task-1", "172.31.0.6");

        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned(childName).orElseThrow());
        assertTrue(cloudMapService.resolveDnsName(childName).isEmpty());
    }

    private String privateDnsNamespace(String name) {
        return cloudMapService.createPrivateDnsNamespace(name, "vpc-dns", null, null, Map.of(), REGION)
                .getTargets().get("NAMESPACE");
    }

    private Service createService(String namespaceId, String serviceName) {
        return cloudMapService.createService(serviceName, namespaceId, null, null,
                null, null, null, null, Map.of(), REGION);
    }

    private void registerInstance(String serviceId, String instanceId, String ipv4) {
        cloudMapService.registerInstance(serviceId, instanceId, null,
                Map.of("AWS_INSTANCE_IPV4", ipv4), REGION);
    }

    private static String uniqueNamespace() {
        return "dnsres" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
    }
}
