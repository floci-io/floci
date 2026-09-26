package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.dns.DnsLookupHelper;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53DnsResolutionTest {

    @Inject
    Route53Service route53Service;

    @InjectMock
    DnsLookupHelper dnsLookupHelper;

    @Inject
    Route53DnsRecordSource dnsRecordSource;

    private final List<String> createdZoneIds = new ArrayList<>();

    @AfterEach
    void cleanupZones() {
        for (String zoneId : createdZoneIds) {
            try {
                List<Map<String, Object>> deletes = route53Service.listResourceRecordSets(zoneId, null, null, 100).stream()
                        .filter(rrs -> !"NS".equals(rrs.getType()) && !"SOA".equals(rrs.getType()))
                        .map(rrs -> Map.<String, Object>of("action", "DELETE", "rrs", rrs))
                        .toList();
                if (!deletes.isEmpty()) {
                    route53Service.changeResourceRecordSets(zoneId, deletes, null);
                }
                route53Service.deleteHostedZone(zoneId);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdZoneIds.clear();
    }

    @Test
    void resolvesDirectARecordInPrivateHostedZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.1.100");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("api." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.1.100"), result.get());
    }

    @Test
    void resolvesMultipleARecords() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecords(zoneId, "api." + zone, "A", List.of("10.0.1.101", "10.0.1.102"));

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("api." + zone);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().contains("10.0.1.101"));
        assertTrue(result.get().contains("10.0.1.102"));
    }

    @Test
    void answersWithAtMostEightRecords() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        List<String> ips = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ips.add("10.0.1." + i);
        }
        addRecords(zoneId, "fleet." + zone, "A", ips);

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("fleet." + zone);
        assertTrue(result.isPresent());
        assertEquals(8, result.get().size());
    }

    @Test
    void resolutionIsCaseInsensitiveAndToleratesATrailingDot() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.1.200");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("API." + zone.toUpperCase() + ".");
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.1.200"), result.get());
    }

    @Test
    void doesNotResolvePublicHostedZone() {
        String zone = uniqueZone();
        String zoneId = route53Service.createHostedZone(
                zone + ".", UUID.randomUUID().toString(), "public zone", null).zone().getId();
        createdZoneIds.add(zoneId);
        addRecord(zoneId, "api." + zone, "A", "1.2.3.4");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("api." + zone);
        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotResolveNameOutsideAnyPrivateZone() {
        assertTrue(dnsRecordSource.resolveIpv4("example.com").isEmpty());
        assertTrue(dnsRecordSource.resolveIpv4("").isEmpty());
        assertTrue(dnsRecordSource.resolveIpv4(null).isEmpty());
    }

    @Test
    void resolvesWildcardRecordInPrivateZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("app." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.2.1"), result.get());
    }

    @Test
    void wildcardDoesNotMatchBeneathCloserExistingName() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "*." + zone, "A", "10.0.2.1");
        addRecord(zoneId, "b." + zone, "TXT", "\"text-record\"");

        // Wildcard should not match a.b.zone because b.zone exists
        Optional<List<String>> result = dnsRecordSource.resolveIpv4("a.b." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of(), result.get());
    }

    @Test
    void resolvesPrivateCnameChainToARecord() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "api." + zone, "A", "10.0.3.50");
        addRecord(zoneId, "service." + zone, "CNAME", "api." + zone + ".");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("service." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.3.50"), result.get());
    }

    @Test
    void handlesCnameCycleGracefullyWithoutInfiniteLoop() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "cname1." + zone, "CNAME", "cname2." + zone);
        addRecord(zoneId, "cname2." + zone, "CNAME", "cname1." + zone);

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("cname1." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of(), result.get());
    }

    @Test
    void resolvesPrivateCnameToEc2PrivateDnsName() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "ec2host." + zone, "CNAME", "ip-10-0-5-88.ec2.internal.");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("ec2host." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.5.88"), result.get());
    }

    @Test
    void resolvesPrivateCnameToExternalHostname() {
        Mockito.when(dnsLookupHelper.resolveIpv4("external.example.com"))
                .thenReturn(List.of("93.184.216.34"));

        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "ext." + zone, "CNAME", "external.example.com.");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("ext." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("93.184.216.34"), result.get());
    }

    @Test
    void resolvesAliasRecordInPrivateZone() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "target." + zone, "A", "10.0.4.99");
        addAlias(zoneId, "alias." + zone, "A", "target." + zone + ".");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("alias." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.4.99"), result.get());
    }

    @Test
    void returnsEmptyListForNonExistentNameInPrivateZone() {
        String zone = uniqueZone();
        createPrivateZone(zone);

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("nonexistent." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of(), result.get());
    }

    @Test
    void returnsEmptyListForExistingNameWithoutARecord() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "txtonly." + zone, "TXT", "\"only text here\"");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("txtonly." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of(), result.get());
    }

    @Test
    void parentZoneDoesNotAnswerChildNamesIfChildZoneExists() {
        String parentZone = uniqueZone();
        String childZone = "sub." + parentZone;
        String parentId = createPrivateZone(parentZone);
        String childId = createPrivateZone(childZone);

        addRecord(parentId, "api.sub." + parentZone, "A", "10.0.10.1");
        addRecord(childId, "api." + childZone, "A", "10.0.20.1");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("api.sub." + parentZone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.20.1"), result.get());
    }

    @Test
    void cnameTargetToFlociBuiltinSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "floci-cname." + zone, "CNAME", "s3.localhost.localstack.cloud");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("floci-cname." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get());
    }

    @Test
    void cnameTargetToDefaultFlociSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addRecord(zoneId, "floci-cname2." + zone, "CNAME", "sqs.localhost.floci.io");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("floci-cname2." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get());
    }

    @Test
    void aliasTargetToFlociBuiltinSuffixResolvesLocalAddress() throws Exception {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "floci-alias." + zone, "A", "s3.localhost.localstack.cloud");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("floci-alias." + zone);
        assertTrue(result.isPresent());
        String expectedIp = InetAddress.getLocalHost().getHostAddress();
        assertEquals(List.of(expectedIp), result.get());
    }

    @Test
    void aliasTargetToBareLocalstackCloudDoesNotResolveLocalAddress() throws Exception {
        Mockito.when(dnsLookupHelper.resolveIpv4("s3.localstack.cloud"))
                .thenReturn(List.of("192.0.2.1"));

        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "bare-alias." + zone, "A", "s3.localstack.cloud");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("bare-alias." + zone);
        assertTrue(result.isPresent());
        String localIp = InetAddress.getLocalHost().getHostAddress();
        assertFalse(result.get().contains(localIp));
        assertEquals(List.of("192.0.2.1"), result.get());
    }

    @Test
    void aliasTargetToEc2PrivateDnsResolves() {
        String zone = uniqueZone();
        String zoneId = createPrivateZone(zone);
        addAlias(zoneId, "ec2-alias." + zone, "A", "ip-10-0-0-5.ec2.internal");

        Optional<List<String>> result = dnsRecordSource.resolveIpv4("ec2-alias." + zone);
        assertTrue(result.isPresent());
        assertEquals(List.of("10.0.0.5"), result.get());
    }

    private String createPrivateZone(String name) {
        String zoneName = name.endsWith(".") ? name : name + ".";
        String id = route53Service.createHostedZone(
                zoneName,
                UUID.randomUUID().toString(),
                "test private zone",
                new VpcAssociation("vpc-12345", "us-east-1")).zone().getId();
        createdZoneIds.add(id);
        return id;
    }

    private void addRecord(String zoneId, String name, String type, String value) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        rrs.setTtl(300L);
        rrs.setRecords(List.of(new ResourceRecord(value)));
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private void addRecords(String zoneId, String name, String type, List<String> values) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        rrs.setTtl(300L);
        rrs.setRecords(values.stream().map(ResourceRecord::new).toList());
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private void addAlias(String zoneId, String name, String type, String targetDnsName) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        AliasTarget alias = new AliasTarget();
        alias.setDnsName(targetDnsName);
        alias.setHostedZoneId(zoneId);
        alias.setEvaluateTargetHealth(false);
        rrs.setAliasTarget(alias);
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", rrs)), null);
    }

    private static String uniqueZone() {
        return "r53dns" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
    }
}
