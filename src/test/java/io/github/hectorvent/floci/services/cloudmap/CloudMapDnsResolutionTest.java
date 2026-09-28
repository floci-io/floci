package io.github.hectorvent.floci.services.cloudmap;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsRecord;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void dnsNamespaceRejectsServiceNamesThatDifferOnlyByCase() {
        String namespaceId = privateDnsNamespace(uniqueNamespace());
        createService(namespaceId, "Api", dnsConfig("{\"Type\":\"A\",\"TTL\":15}"));

        AwsException error = assertThrows(AwsException.class, () -> createService(
                namespaceId, "api", dnsConfig("{\"Type\":\"A\",\"TTL\":300}")));
        assertEquals("ServiceAlreadyExists", error.getErrorCode());
    }

    @Test
    void httpNamespaceAllowsServiceNamesThatDifferOnlyByCase() {
        String namespaceId = cloudMapService.createHttpNamespace(uniqueNamespace(), null, null,
                Map.of(), REGION).getTargets().get("NAMESPACE");

        createService(namespaceId, "Api");
        createService(namespaceId, "api");

        assertEquals(2, cloudMapService.listServices(REGION, namespaceId).size());
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
        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned("empty." + namespace).orElseThrow().addresses());
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
        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned(namespace).orElseThrow().addresses());
    }

    @Test
    void nestedNamespaceApexDoesNotResolveThroughTheParentNamespace() {
        String parentName = uniqueNamespace();
        String childName = "nested." + parentName;
        String parentId = privateDnsNamespace(parentName);
        privateDnsNamespace(childName);
        Service parentService = createService(parentId, "nested");
        registerInstance(parentService.getId(), "task-1", "172.31.0.6");

        assertEquals(List.of(), cloudMapService.resolveDnsNameIfOwned(childName).orElseThrow().addresses());
        assertTrue(cloudMapService.resolveDnsName(childName).isEmpty());
    }

    // ── TTL ───────────────────────────────────────────────────────────────────

    @Test
    void answersWithTheTtlTheServicesARecordDeclares() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "valkey",
                dnsConfig("{\"Type\":\"A\",\"TTL\":15}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(15, cloudMapService.resolveDnsNameIfOwned("valkey." + namespace).orElseThrow().ttlSeconds());
    }

    @Test
    void prefersTheARecordsTtlOverAnotherRecordTypes() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "api",
                dnsConfig("{\"Type\":\"AAAA\",\"TTL\":300}", "{\"Type\":\"A\",\"TTL\":15}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(15, cloudMapService.resolveDnsNameIfOwned("api." + namespace).orElseThrow().ttlSeconds());
    }

    @Test
    void srvOnlyServiceDoesNotPublishAnARecord() {
        // Cloud Map creates SRV records for the service name, not an A record there.
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "srvonly",
                dnsConfig("{\"Type\":\"SRV\",\"TTL\":300}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertTrue(cloudMapService.resolveDnsName("srvonly." + namespace).isEmpty());
        DnsAnswer answer = cloudMapService.resolveDnsNameIfOwned("srvonly." + namespace).orElseThrow();
        assertTrue(answer.isEmpty());
        assertTrue(answer.nameExists());
    }

    @Test
    void srvInstanceHostnamePublishesItsOwnIpv4Address() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "backend",
                dnsConfig("{\"Type\":\"SRV\",\"TTL\":300}"));
        cloudMapService.registerInstance(service.getId(), "task.one", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6", "AWS_INSTANCE_PORT", "8080"), REGION);
        cloudMapService.registerInstance(service.getId(), "task-two", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.7", "AWS_INSTANCE_PORT", "8080"), REGION);

        DnsAnswer answer = cloudMapService.resolveDnsNameIfOwned(
                "TASK.ONE.BACKEND." + namespace.toUpperCase() + ".").orElseThrow();
        assertEquals(List.of("172.31.0.6"), answer.addresses());
        assertEquals(300, answer.ttlSeconds());
        assertFalse(cloudMapService.resolveDnsNameIfOwned("missing.backend." + namespace)
                .orElseThrow().nameExists());

        cloudMapService.deregisterInstance(service.getId(), "task.one", REGION);
        assertFalse(cloudMapService.resolveDnsNameIfOwned("task.one.backend." + namespace)
                .orElseThrow().nameExists());
    }

    @Test
    void resolvesNamesWithAnIRegardlessOfTheDefaultLocale() {
        String namespace = uniqueNamespace();
        String namespaceId = privateDnsNamespace(namespace);
        Service srv = createService(namespaceId, "Inventory", dnsConfig("{\"Type\":\"SRV\",\"TTL\":300}"));
        registerInstance(srv.getId(), "task", "172.31.0.6");
        Service a = createService(namespaceId, "Billing");
        registerInstance(a.getId(), "i-1", "172.31.0.7");

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals(List.of("172.31.0.6"), cloudMapService.resolveDnsName("task.inventory." + namespace));
            assertEquals(List.of("172.31.0.6"), cloudMapService.resolveDnsName("TASK.INVENTORY." + namespace));
            assertEquals(List.of("172.31.0.7"), cloudMapService.resolveDnsName("BILLING." + namespace));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void fallsBackToTheDefaultTtlWhenTheServiceHasNoDnsConfig() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "plain");
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(DnsAnswer.DEFAULT_TTL_SECONDS,
                cloudMapService.resolveDnsNameIfOwned("plain." + namespace).orElseThrow().ttlSeconds());
    }

    @Test
    void rejectsTtlValuesOutsideTheAwsRange() {
        String namespaceId = privateDnsNamespace(uniqueNamespace());
        for (String ttl : List.of("-1", "2147483648", "1.5", "\"15\"")) {
            AwsException error = assertThrows(AwsException.class, () -> createService(
                    namespaceId, "invalid", dnsConfig("{\"Type\":\"A\",\"TTL\":" + ttl + "}")));
            assertEquals("InvalidInput", error.getErrorCode());
        }
    }

    @Test
    void rejectsDnsRecordsWithoutATtl() {
        String namespaceId = privateDnsNamespace(uniqueNamespace());
        AwsException error = assertThrows(AwsException.class, () -> createService(
                namespaceId, "missing", dnsConfig("{\"Type\":\"A\"}")));
        assertEquals("InvalidInput", error.getErrorCode());
    }

    @Test
    void acceptsATtlOfZero() {
        // Cloud Map's range starts at 0, and a resolver told 0 must not cache the answer at all.
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "nocache",
                dnsConfig("{\"Type\":\"A\",\"TTL\":0}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(0, cloudMapService.resolveDnsNameIfOwned("nocache." + namespace).orElseThrow().ttlSeconds());
    }

    @Test
    void acceptsTheMaximumAwsTtl() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "longcache",
                dnsConfig("{\"Type\":\"A\",\"TTL\":2147483647}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");

        assertEquals(Integer.MAX_VALUE,
                cloudMapService.resolveDnsNameIfOwned("longcache." + namespace).orElseThrow().ttlSeconds());
    }

    @Test
    void rejectsMalformedDnsConfig() {
        String namespaceId = privateDnsNamespace(uniqueNamespace());
        AwsException error = assertThrows(AwsException.class,
                () -> createService(namespaceId, "malformed", "{not json"));
        assertEquals("InvalidInput", error.getErrorCode());
    }

    private static String dnsConfig(String... records) {
        return "{\"DnsRecords\":[" + String.join(",", records) + "]}";
    }

    private String privateDnsNamespace(String name) {
        return cloudMapService.createPrivateDnsNamespace(name, "vpc-dns", null, null, Map.of(), REGION)
                .getTargets().get("NAMESPACE");
    }

    private Service createService(String namespaceId, String serviceName) {
        return createService(namespaceId, serviceName, null);
    }

    private Service createService(String namespaceId, String serviceName, String dnsConfig) {
        return cloudMapService.createService(serviceName, namespaceId, null, null,
                dnsConfig, null, null, null, Map.of(), REGION);
    }

    private void registerInstance(String serviceId, String instanceId, String ipv4) {
        cloudMapService.registerInstance(serviceId, instanceId, null,
                Map.of("AWS_INSTANCE_IPV4", ipv4), REGION);
    }

    private static String uniqueNamespace() {
        return "dnsres" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
    }

    @Test
    void dualStackRecordsHaveIndependentTtlsAndNoDataForOtherTypes() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "dual",
                dnsConfig("{\"Type\":\"A\",\"TTL\":15},{\"Type\":\"AAAA\",\"TTL\":300}"));
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6", "AWS_INSTANCE_IPV6", "2001:db8::1"), REGION);
        DnsAnswer aaaa = cloudMapService.resolveDnsNameIfOwned("dual." + namespace, 28).orElseThrow();
        assertEquals(300, aaaa.ttlSeconds());
        assertEquals(List.of(new DnsRecord.Address(28, "2001:db8::1")), aaaa.records());
        assertEquals(15, cloudMapService.resolveDnsNameIfOwned("dual." + namespace).orElseThrow().ttlSeconds());
        DnsAnswer txt = cloudMapService.resolveDnsNameIfOwned("dual." + namespace, 16).orElseThrow();
        assertTrue(txt.nameExists());
        assertTrue(txt.isEmpty());
        assertFalse(cloudMapService.resolveDnsNameIfOwned("missing." + namespace, 28).orElseThrow().nameExists());
    }

    @Test
    void weightedRoutingSelectsHealthyInstancesAndFallsBackWhenAllAreUnhealthy() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "weighted",
                "{\"RoutingPolicy\":\"WEIGHTED\",\"DnsRecords\":[{\"Type\":\"AAAA\",\"TTL\":15}]}");
        for (int i = 1; i <= 3; i++) {
            cloudMapService.registerInstance(service.getId(), "task-" + i, null,
                    Map.of("AWS_INSTANCE_IPV6", "2001:db8::" + i), REGION);
        }
        cloudMapService.getInstance(service.getId(), "task-1").setHealthStatus("UNHEALTHY");
        Set<DnsRecord> sampled = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            DnsAnswer answer = cloudMapService.resolveDnsNameIfOwned("weighted." + namespace, 28).orElseThrow();
            assertEquals(1, answer.records().size());
            assertFalse(answer.records().contains(new DnsRecord.Address(28, "2001:db8::1")));
            sampled.addAll(answer.records());
        }
        assertEquals(2, sampled.size());
        cloudMapService.getInstance(service.getId(), "task-2").setHealthStatus("UNHEALTHY");
        cloudMapService.getInstance(service.getId(), "task-3").setHealthStatus("UNHEALTHY");
        assertEquals(1, cloudMapService.resolveDnsNameIfOwned("weighted." + namespace, 28).orElseThrow().records().size());
    }

    @Test
    void invalidIpv6AndSrvPortsDoNotProduceMalformedPackets() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "invalid",
                dnsConfig("{\"Type\":\"SRV\",\"TTL\":15}"));
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV6", "not-an-address", "AWS_INSTANCE_PORT", "70000"), REGION);
        assertTrue(cloudMapService.resolveDnsNameIfOwned("invalid." + namespace, 33).orElseThrow().isEmpty());
        assertTrue(cloudMapService.resolveDnsNameIfOwned("task-1.invalid." + namespace, 28).orElseThrow().isEmpty());
    }

    @Test
    void legacyMixedCnameConfigurationDoesNotHideConfiguredAddresses() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "mixed",
                dnsConfig("{\"Type\":\"A\",\"TTL\":15},{\"Type\":\"CNAME\",\"TTL\":45}"));
        registerInstance(service.getId(), "task-1", "172.31.0.6");
        DnsAnswer answer = cloudMapService.resolveDnsNameIfOwned("mixed." + namespace, 1).orElseThrow();
        assertEquals(List.of("172.31.0.6"), answer.addresses());
        assertEquals(15, answer.ttlSeconds());
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6", "AWS_INSTANCE_CNAME", "backend.example.com"), REGION);
        assertEquals(List.of("172.31.0.6"), cloudMapService.resolveDnsName("mixed." + namespace));
        assertEquals(List.of(new DnsRecord.Cname("backend.example.com")),
                cloudMapService.resolveDnsNameIfOwned("mixed." + namespace, 5).orElseThrow().records());
    }

    @Test
    void srvRegistrationRejectsUnencodableTargetsWithoutChangingInstanceState() {
        String namespace = uniqueNamespace();
        Service service = createService(privateDnsNamespace(namespace), "srv",
                dnsConfig("{\"Type\":\"SRV\",\"TTL\":15}"));
        Map<String, String> attributes = Map.of("AWS_INSTANCE_PORT", "8080", "AWS_INSTANCE_IPV4", "192.0.2.1");
        cloudMapService.registerInstance(service.getId(), "a".repeat(63), null, attributes, REGION);
        long revision = service.getRevision();
        AwsException error = assertThrows(AwsException.class, () -> cloudMapService.registerInstance(
                service.getId(), "a".repeat(64), null, attributes, REGION));
        assertEquals("InvalidInput", error.getErrorCode());
        assertEquals(1, service.getInstanceCount());
        assertEquals(revision, service.getRevision());
        assertEquals(1, cloudMapService.resolveDnsNameIfOwned("srv." + namespace, 33).orElseThrow().records().size());
        // The DNS target restriction does not shorten the API's general identifier limit.
        Service aService = createService(service.getNamespaceId(), "addresses");
        registerInstance(aService.getId(), "a".repeat(64), "192.0.2.2");
        assertEquals(1, aService.getInstanceCount());
    }
}
