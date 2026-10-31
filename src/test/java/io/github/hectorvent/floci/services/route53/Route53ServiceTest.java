package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsRecord;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53ServiceTest {

    private static final String ACCOUNT = "000000000000";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

    private Route53Service newService() {
        return newService(".");
    }

    private Route53Service newService(String nameserverSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    AccountAwareStorageBackend<Object> store = Mockito.spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
                    stores.put(invocation.getArgument(1), store);
                    return store;
                });

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Route53ServiceConfig route53Config =
                Mockito.mock(EmulatorConfig.Route53ServiceConfig.class);

        Mockito.when(config.defaultAccountId()).thenReturn(ACCOUNT);
        Mockito.when(config.services()).thenReturn(servicesConfig);
        Mockito.when(servicesConfig.route53()).thenReturn(route53Config);
        Mockito.when(route53Config.defaultNameserver1()).thenReturn("ns-1.awsdns-00.com" + nameserverSuffix);
        Mockito.when(route53Config.defaultNameserver2()).thenReturn("ns-2.awsdns-00.net" + nameserverSuffix);
        Mockito.when(route53Config.defaultNameserver3()).thenReturn("ns-3.awsdns-00.org" + nameserverSuffix);
        Mockito.when(route53Config.defaultNameserver4()).thenReturn("ns-4.awsdns-00.co.uk" + nameserverSuffix);
        Mockito.when(route53Config.vpcAssociationControlPlaneDelayMs()).thenReturn(0L);

        Ec2Service ec2Service = Mockito.mock(Ec2Service.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", ACCOUNT);

        return new Route53Service(storageFactory, config, regionResolver, ec2Service);
    }

    @Test
    void hostedZonesByNameUseReversedLabelsAndFilteredPageBoundary() {
        Route53Service service = newService();
        String firstId = service.createHostedZone("z.example.com.", "byname-first", null, null).zone().getId();
        String lastId = service.createHostedZone("a.other.com.", "byname-last", null, null).zone().getId();

        assertEquals(List.of(firstId, lastId), service.listHostedZonesByName(null, 0).stream()
                .map(HostedZone::getId).toList());

        Route53Pagination.Page<HostedZone> lastPage = service.pageHostedZonesByName("a.other.com.", null, 1);
        assertEquals(List.of(lastId), lastPage.items().stream().map(HostedZone::getId).toList());
        assertFalse(lastPage.isTruncated());
    }

    @Test
    void reversedDnsNameKeepsAnEscapedDotInsideItsLabel() {
        assertEquals("com.example.a\\.b.", Route53Service.reversedDnsName("a\\.b.example.com."));
    }

    @Test
    void hostedZonesByNameContinueAcrossNamesAndLimitPagesToOneHundred() {
        Route53Service service = newService();
        for (int i = 0; i < 101; i++) {
            service.createHostedZone("zone-" + String.format("%03d", i) + ".example.test.",
                    "byname-cap-" + i, null, null);
        }

        assertEquals(101, service.listHostedZonesByName(null, 0).size());
        Route53Pagination.Page<HostedZone> firstPage = service.pageHostedZonesByName(null, null, 200);
        assertEquals(100, firstPage.items().size());
        assertTrue(firstPage.isTruncated());

        HostedZone nextZone = firstPage.nextItem();
        Route53Pagination.Page<HostedZone> secondPage = service.pageHostedZonesByName(
                nextZone.getName(), nextZone.getId(), 200);
        assertEquals(List.of(nextZone.getId()), secondPage.items().stream().map(HostedZone::getId).toList());
        assertFalse(secondPage.isTruncated());
    }

    @ParameterizedTest(name = "configured names ending in {0}")
    @ValueSource(strings = {"", ".", " ", ".\t", ".\u2003"})
    void defaultApexRecordsUseAbsoluteNamesAndTheDelegationSetDoesNot(String nameserverSuffix) {
        Route53Service service = newService(nameserverSuffix);
        String zoneId = service.createHostedZone("authority.example", "authority", null, null).zone().getId();
        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, null, null, 100);

        assertAll(
                () -> assertEquals(List.of("ns-1.awsdns-00.com. awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400"),
                        valuesOf(records, "SOA"), "SOA"),
                () -> assertEquals(List.of("ns-1.awsdns-00.com.", "ns-2.awsdns-00.net.", "ns-3.awsdns-00.org.",
                        "ns-4.awsdns-00.co.uk."), valuesOf(records, "NS"), "NS"),
                () -> assertEquals(List.of("ns-1.awsdns-00.com", "ns-2.awsdns-00.net", "ns-3.awsdns-00.org",
                        "ns-4.awsdns-00.co.uk"), service.getNameServers(), "delegation set"));
    }

    @ParameterizedTest(name = "configured names ending in {0}")
    @ValueSource(strings = {"", ".", " ", ".\t", ".\u2003"})
    void apexNsQueryIsAnswered(String nameserverSuffix) {
        Route53Service service = newService(nameserverSuffix);
        service.createHostedZone("authority.example", "authority", null, null);

        DnsAnswer answer = new Route53DnsRecordSource(service).resolveAuthoritative("authority.example", 2)
                .orElseThrow();

        assertEquals(0, answer.responseCode(), "rcode");
        assertEquals(List.of(new DnsRecord.Ns("ns-1.awsdns-00.com."), new DnsRecord.Ns("ns-2.awsdns-00.net."),
                new DnsRecord.Ns("ns-3.awsdns-00.org."), new DnsRecord.Ns("ns-4.awsdns-00.co.uk.")),
                answer.records(), "answers");
    }

    @Test
    void rejectedBatchDoesNotRenameSharedStoredRecord() {
        Route53Service service = newService();
        String zone = service.createHostedZone("EXAMPLE.COM", "shared-record", null, null).zone().getId();
        ResourceRecordSet stored = service.listResourceRecordSets(zone, null, null, 100).stream()
                .filter(record -> "NS".equals(record.getType())).findFirst().orElseThrow();
        String originalName = stored.getName();
        assertEquals("EXAMPLE.COM.", originalName);
        assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zone,
                List.of(change("UPSERT", stored), change("DELETE", aRecord("missing.example.com.", 60, "192.0.2.1"))), null));
        assertEquals(originalName, stored.getName());
        assertEquals(originalName, service.listResourceRecordSets(zone, null, null, 100).stream()
                .filter(record -> "NS".equals(record.getType())).findFirst().orElseThrow().getName());
        service.changeResourceRecordSets(zone, List.of(change("UPSERT", stored)), null);
        assertEquals(originalName, stored.getName());
        assertEquals("example.com.", service.listResourceRecordSets(zone, null, null, 100).stream()
                .filter(record -> "NS".equals(record.getType())).findFirst().orElseThrow().getName());
    }

    @Test
    void publicZoneIndexAvoidsStorageScansOutsideOwnedNamesAndTracksDeletion() {
        Route53Service service = newService();
        AccountAwareStorageBackend<?> zones = stores.get("route53-zones.json");
        Mockito.clearInvocations(zones);
        assertTrue(service.publicDnsZones("outside.example").isEmpty());
        Mockito.verifyNoInteractions(zones);
        String first = service.createHostedZone("owned.example", "first", null, null).zone().getId();
        String second = service.createHostedZone("owned.example", "second", null, null).zone().getId();
        Mockito.clearInvocations(zones);
        assertTrue(service.publicDnsZones("outside.example").isEmpty());
        Mockito.verifyNoInteractions(zones);
        assertEquals(2, service.publicDnsZones("text.owned.example").size());
        service.deleteHostedZone(first);
        assertEquals(1, service.publicDnsZones("text.owned.example").size());
        service.deleteHostedZone(second);
        Mockito.clearInvocations(zones);
        assertTrue(service.publicDnsZones("text.owned.example").isEmpty());
        Mockito.verifyNoInteractions(zones);
    }

    private static List<String> valuesOf(List<ResourceRecordSet> records, String type) {
        return records.stream()
                .filter(rrs -> type.equals(rrs.getType()))
                .flatMap(rrs -> rrs.getRecords().stream())
                .map(ResourceRecord::getValue)
                .toList();
    }

    @Test
    void recordIdentityFoldsOnlyAsciiLettersAndPreservesPolicyIdentity() {
        ResourceRecordSet upper = weightedRecord("WWW.example.com", 60, "Blue", 1, "192.0.2.1");
        ResourceRecordSet lower = weightedRecord("www.example.com.", 60, "Blue", 1, "192.0.2.1");
        assertTrue(Route53Service.sameRecord(upper, lower));
        lower.setSetIdentifier("blue");
        assertFalse(Route53Service.sameRecord(upper, lower));
        lower.setSetIdentifier("Blue");
        lower.setType("AAAA");
        assertFalse(Route53Service.sameRecord(upper, lower));
        assertFalse(Route53Service.sameRecord(aRecord("Ä.example", 60), aRecord("ä.example", 60)));
        assertFalse(Route53Service.sameRecord(aRecord("\\127.example", 60), aRecord("w.example", 60)));
    }

    @Test
    void caseVariantCreateCannotDuplicateOrPartiallyCommitBatch() {
        Route53Service service = newService();
        String zone = service.createHostedZone("example.com", "case-duplicate", null, null).zone().getId();
        service.changeResourceRecordSets(zone, List.of(change("CREATE", aRecord("www.example.com", 60, "192.0.2.1"))), null);
        AwsException error = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zone, List.of(
                change("CREATE", aRecord("other.example.com", 60, "192.0.2.2")),
                change("CREATE", aRecord("WWW.EXAMPLE.COM.", 60, "192.0.2.3"))), null));
        assertEquals("InvalidChangeBatch", error.getErrorCode());
        assertEquals(3, service.listResourceRecordSets(zone, null, null, 100).size());
    }

    @Test
    void caseVariantUpsertAndDeleteRetainWeightedSiblingAndRejectWrongValue() {
        Route53Service service = newService();
        String zone = service.createHostedZone("example.com", "case-weight", null, null).zone().getId();
        service.changeResourceRecordSets(zone, List.of(
                change("CREATE", weightedRecord("WWW.example.com", 60, "Blue", 1, "192.0.2.1")),
                change("CREATE", weightedRecord("www.example.com", 60, "blue", 2, "192.0.2.2"))), null);
        service.changeResourceRecordSets(zone, List.of(change("UPSERT",
                weightedRecord("WwW.example.com", 60, "Blue", 3, "192.0.2.3"))), null);
        assertEquals(4, service.listResourceRecordSets(zone, null, null, 100).size());
        assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zone, List.of(change("DELETE",
                weightedRecord("WWW.example.com", 60, "Blue", 3, "192.0.2.4"))), null));
        service.changeResourceRecordSets(zone, List.of(change("DELETE",
                weightedRecord("WWW.example.com", 60, "Blue", 3, "192.0.2.3"))), null);
        List<ResourceRecordSet> remaining = service.listResourceRecordSets(zone, "www.example.com", "A", 100);
        assertEquals(1, remaining.size());
        assertEquals("blue", remaining.getFirst().getSetIdentifier());
        assertEquals("www.example.com.", remaining.getFirst().getName());
    }

    @Test
    void caseVariantApexDeleteRemainsForbidden() {
        Route53Service service = newService();
        String zone = service.createHostedZone("Example.COM", "case-apex", null, null).zone().getId();
        ResourceRecordSet ns = service.listResourceRecordSets(zone, null, null, 100).stream()
                .filter(record -> "NS".equals(record.getType())).findFirst().orElseThrow();
        ResourceRecordSet request = new ResourceRecordSet();
        request.setName("EXAMPLE.com");
        request.setType("NS");
        request.setTtl(ns.getTtl());
        request.setRecords(ns.getRecords());
        AwsException error = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(
                zone, List.of(change("DELETE", request)), null));
        assertTrue(error.getMessage().contains("apex"));
        assertEquals(2, service.listResourceRecordSets(zone, null, null, 100).size());
    }

    @Test
    void recordListingSortsAndFiltersWithTheSameNormalizedName() {
        Route53Service service = newService();
        String zone = service.createHostedZone("EXAMPLE.com", "case-page", null, null).zone().getId();
        service.changeResourceRecordSets(zone, List.of(change("CREATE",
                aRecord("a.example.com", 60, "192.0.2.1"))), null);
        List<ResourceRecordSet> page = service.listResourceRecordSets(zone, "A.EXAMPLE.COM", "A", 1);
        assertEquals(1, page.size());
        assertEquals("a.example.com.", page.getFirst().getName());
        assertEquals("A", page.getFirst().getType());
    }

    private static Map<String, Object> change(String action, ResourceRecordSet rrs) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        m.put("rrs", rrs);
        return m;
    }

    private static ResourceRecordSet aRecord(String name, long ttl, String... values) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType("A");
        rrs.setTtl(ttl);
        List<ResourceRecord> records = new ArrayList<>();
        for (String value : values) {
            records.add(new ResourceRecord(value));
        }
        rrs.setRecords(records);
        return rrs;
    }

    private static ResourceRecordSet aliasRecord(String name, String dnsName, boolean evaluateTargetHealth) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType("A");
        AliasTarget alias = new AliasTarget();
        alias.setHostedZoneId("Z1ALIASZONE");
        alias.setDnsName(dnsName);
        alias.setEvaluateTargetHealth(evaluateTargetHealth);
        rrs.setAliasTarget(alias);
        return rrs;
    }

    private static ResourceRecordSet weightedRecord(String name, long ttl, String setIdentifier, long weight,
                                                      String... values) {
        ResourceRecordSet rrs = aRecord(name, ttl, values);
        rrs.setSetIdentifier(setIdentifier);
        rrs.setWeight(weight);
        return rrs;
    }

    @Test
    void recordOutsideTheHostedZoneIsRejected() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("datacite.org.", "ref-outside", null, null).zone().getId();

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("_lovable.strategy", 300, "1.2.3.4"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("RRSet with DNS name _lovable.strategy. is not permitted in zone datacite.org.", e.getMessage());
        AwsException suffixOnly = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("notdatacite.org", 300, "1.2.3.4"))), null));
        assertEquals("InvalidChangeBatch", suffixOnly.getErrorCode());
    }

    @Test
    void recordInsideTheHostedZoneIsAcceptedRegardlessOfCaseDotOrWildcard() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("datacite.org.", "ref-inside", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId, List.of(
                change("CREATE", aRecord("WWW.DataCite.org", 300, "1.2.3.4")),
                change("CREATE", aRecord("*.blog.datacite.org.", 300, "1.2.3.5")),
                change("CREATE", aRecord("datacite.org", 300, "1.2.3.6"))), null);

        assertEquals(5, service.listResourceRecordSets(zoneId, null, null, 0).size());
    }

    @Test
    void rootHostedZoneAcceptsAnyRecordName() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone(".", "ref-root", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.example.", 300, "1.2.3.4"))), null);

        assertEquals(3, service.listResourceRecordSets(zoneId, null, null, 0).size());
    }

    @Test
    void zoneCheckFoldsOnlyAsciiLettersLikeRecordIdentity() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("k.example.", "ref-kelvin", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.K.example.", 300, "1.2.3.4"))), null);
        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.\u212A.example.", 300, "1.2.3.5"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
    }

    @Test
    void deleteFailsWithNotFoundMessageWhenNoRecordWithMatchingNameAndTypeExists() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-missing", null, null).zone().getId();

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aRecord("missing.example.com.", 300, "1.2.3.4"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='missing.example.com.', type='A'] "
                + "but it was not found", e.getMessage());
    }

    @Test
    void deleteFailsWhenResourceRecordValueDoesNotMatch() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-value", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.example.com.", 300, "1.2.3.4"))), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aRecord("www.example.com.", 300, "5.6.7.8"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='www.example.com.', type='A'] "
                + "but the values provided do not match the current values", e.getMessage());

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "www.example.com.", "A", 1);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getRecords().get(0).getValue(), equalTo("1.2.3.4"));
    }

    @Test
    void deleteFailsWhenTtlDoesNotMatch() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-ttl", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.example.com.", 300, "1.2.3.4"))), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aRecord("www.example.com.", 60, "1.2.3.4"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='www.example.com.', type='A'] "
                + "but the values provided do not match the current values", e.getMessage());

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "www.example.com.", "A", 1);
        assertThat(records, hasSize(1));
        assertEquals(300L, records.get(0).getTtl());
    }

    @Test
    void deleteFailsWhenAliasTargetDoesNotMatch() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-alias", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aliasRecord("alias.example.com.", "target1.example.com.", true))), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aliasRecord("alias.example.com.", "target2.example.com.", true))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='alias.example.com.', type='A'] "
                + "but the values provided do not match the current values", e.getMessage());

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "alias.example.com.", "A", 1);
        assertThat(records, hasSize(1));
        assertEquals("target1.example.com.", records.get(0).getAliasTarget().getDnsName());
    }

    @ParameterizedTest
    @CsvSource({"target.example.com,target.example.com.",
            "target.example.com.,target.example.com",
            "Target.Example.COM,target.example.com.", "\u212a.Example.COM,\u212a.example.com."})
    void aliasDeletionAcceptsEquivalentDnsSpelling(String storedName, String requestedName) {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-alias-spelling", null, null).zone().getId();
        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aliasRecord("alias.example.com.", storedName, false))), null);

        service.changeResourceRecordSets(zoneId, List.of(
                change("UPSERT", aRecord("marker.example.com.", 60, "192.0.2.21")),
                change("DELETE", aliasRecord("alias.example.com.", requestedName, false))), null);

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, null, null, 100);
        assertFalse(records.stream().anyMatch(record -> "alias.example.com.".equals(record.getName())));
        assertTrue(records.stream().anyMatch(record -> "marker.example.com.".equals(record.getName())));
    }

    @ParameterizedTest
    @CsvSource({"\u212a.example.com,k.example.com", "\u017f.example.com,s.example.com",
            "\u0130.example.com,i.example.com", "\u0131.example.com,I.example.com",
            "\u00c4.example.com,\u00e4.example.com"})
    void aliasDeletionRejectsUnicodeCaseEquivalenceAtomically(String storedName, String requestedName) {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-alias-unicode", null, null).zone().getId();
        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aliasRecord("alias.example.com.", storedName, false))), null);

        AwsException error = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("UPSERT", aRecord("marker.example.com.", 60, "192.0.2.21")),
                        change("DELETE", aliasRecord("alias.example.com.", requestedName, false))), null));

        assertEquals("InvalidChangeBatch", error.getErrorCode());
        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, null, null, 100);
        assertFalse(records.stream().anyMatch(record -> "marker.example.com.".equals(record.getName())));
        assertEquals(storedName, records.stream().filter(record -> "alias.example.com.".equals(record.getName()))
                .findFirst().orElseThrow().getAliasTarget().getDnsName());
    }

    @ParameterizedTest
    @CsvSource({"ZOTHER,false", "Z1ALIASZONE,true"})
    void aliasDeletionStillRejectsOtherTargetDifferencesAtomically(String zone, boolean health) {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-alias-mismatch", null, null).zone().getId();
        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aliasRecord("alias.example.com.", "target.example.com", false))), null);
        ResourceRecordSet requested = aliasRecord("alias.example.com.", "TARGET.example.com.", health);
        requested.getAliasTarget().setHostedZoneId(zone);

        AwsException error = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("UPSERT", aRecord("marker.example.com.", 60, "192.0.2.21")),
                        change("DELETE", requested)), null));

        assertEquals("InvalidChangeBatch", error.getErrorCode());
        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, null, null, 100);
        assertFalse(records.stream().anyMatch(record -> "marker.example.com.".equals(record.getName())));
        ResourceRecordSet retained = records.stream().filter(record -> "alias.example.com.".equals(record.getName()))
                .findFirst().orElseThrow();
        assertEquals("target.example.com", retained.getAliasTarget().getDnsName());
        assertEquals("Z1ALIASZONE", retained.getAliasTarget().getHostedZoneId());
        assertFalse(retained.getAliasTarget().isEvaluateTargetHealth());
    }

    @Test
    void deleteFailsWhenSetIdentifierDoesNotMatch() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-setid", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE",
                        weightedRecord("weighted.example.com.", 60, "primary", 10, "1.1.1.1"))), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE",
                        weightedRecord("weighted.example.com.", 60, "secondary", 10, "1.1.1.1"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='weighted.example.com.', type='A', "
                + "set-identifier='secondary'] but it was not found", e.getMessage());

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "weighted.example.com.", "A", 1);
        assertThat(records, hasSize(1));
        assertEquals("primary", records.get(0).getSetIdentifier());
    }

    @Test
    void deleteSucceedsWhenAllValuesMatchExactly() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-exact", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE",
                        weightedRecord("weighted.example.com.", 60, "primary", 10, "1.1.1.1"))), null);

        service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE",
                        weightedRecord("weighted.example.com.", 60, "primary", 10, "1.1.1.1"))), null);

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "weighted.example.com.", "A", 1);
        assertThat(records, empty());
    }

    @Test
    void changeBatchLeavesRecordsUnchangedWhenALaterChangeFails() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-atomic", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId, List.of(
                change("CREATE", aRecord("a.example.com.", 60, "1.1.1.1")),
                change("CREATE", aRecord("b.example.com.", 60, "2.2.2.2"))
        ), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId, List.of(
                change("DELETE", aRecord("a.example.com.", 60, "1.1.1.1")),
                change("DELETE", aRecord("b.example.com.", 60, "9.9.9.9"))
        ), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='b.example.com.', type='A'] "
                + "but the values provided do not match the current values", e.getMessage());

        List<ResourceRecordSet> aRecords = service.listResourceRecordSets(zoneId, "a.example.com.", "A", 1);
        List<ResourceRecordSet> bRecords = service.listResourceRecordSets(zoneId, "b.example.com.", "A", 1);
        assertThat(aRecords, hasSize(1));
        assertThat(bRecords, hasSize(1));
        assertEquals("1.1.1.1", aRecords.get(0).getRecords().get(0).getValue());
        assertEquals("2.2.2.2", bRecords.get(0).getRecords().get(0).getValue());
    }

    @Test
    void deletingTheSameRecordSetTwiceInOneBatchFailsWithInvalidChangeBatch() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-double-delete", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("dup.example.com.", 60, "1.1.1.1"))), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId, List.of(
                change("DELETE", aRecord("dup.example.com.", 60, "1.1.1.1")),
                change("DELETE", aRecord("dup.example.com.", 60, "1.1.1.1"))
        ), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());
        assertEquals("Tried to delete resource record set [name='dup.example.com.', type='A'] "
                + "but it was not found", e.getMessage());

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "dup.example.com.", "A", 1);
        assertThat(records, hasSize(1));
    }

    @Test
    void aNameWithOrWithoutTheTrailingDotAndABlankSetIdentifierAreTheSameRecord() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-fqdn", null, null).zone().getId();
        // How an earlier CloudFormation provisioner stored a record: the template's undotted Name and
        // empty SetIdentifier, as written.
        ResourceRecordSet legacy = aRecord("www.example.com", 300, "1.2.3.4");
        legacy.setSetIdentifier("");
        service.changeResourceRecordSets(zoneId, List.of(change("CREATE", legacy)), null);

        AwsException e = assertThrows(AwsException.class, () -> service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.example.com.", 300, "5.6.7.8"))), null));
        assertEquals("InvalidChangeBatch", e.getErrorCode());

        service.changeResourceRecordSets(zoneId,
                List.of(change("UPSERT", aRecord("www.example.com.", 300, "5.6.7.8"))), null);
        List<ResourceRecordSet> records = typeARecords(service, zoneId);
        assertThat(records, hasSize(1));
        assertEquals("www.example.com.", records.get(0).getName());
        assertEquals("5.6.7.8", records.get(0).getRecords().get(0).getValue());

        ResourceRecordSet undotted = aRecord("www.example.com", 300, "5.6.7.8");
        undotted.setSetIdentifier("");
        service.changeResourceRecordSets(zoneId, List.of(change("DELETE", undotted)), null);
        assertThat(typeARecords(service, zoneId), empty());
    }

    @Test
    void aDeleteRemovesOneRecordWhenAnEarlierVersionLeftItStoredBothWays() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-twins", null, null).zone().getId();
        // An earlier version compared names literally, so a stack's undotted record and an API caller's
        // dotted one could both be stored, with the same values.
        ResourceRecordSet legacy = aRecord("www.example.com", 300, "1.2.3.4");
        legacy.setSetIdentifier("");
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<List<ResourceRecordSet>> recordStore =
                (AccountAwareStorageBackend<List<ResourceRecordSet>>) stores.get("route53-records.json");
        List<ResourceRecordSet> seeded = new ArrayList<>(recordStore.get(zoneId).orElseThrow());
        seeded.add(legacy);
        seeded.add(aRecord("www.example.com.", 300, "1.2.3.4"));
        recordStore.put(zoneId, seeded);

        service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aRecord("www.example.com.", 300, "1.2.3.4"))), null);
        List<ResourceRecordSet> records = typeARecords(service, zoneId);
        assertThat(records, hasSize(1));
        assertEquals("www.example.com", records.get(0).getName());

        service.changeResourceRecordSets(zoneId,
                List.of(change("DELETE", aRecord("www.example.com.", 300, "1.2.3.4"))), null);
        assertThat(typeARecords(service, zoneId), empty());
    }

    private static List<ResourceRecordSet> typeARecords(Route53Service service, String zoneId) {
        return service.listResourceRecordSets(zoneId, null, null, 0).stream()
                .filter(rrs -> "A".equals(rrs.getType()))
                .toList();
    }

    @Test
    void deleteThenCreateOfSameNameAndTypeInOneBatchSucceeds() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("example.com.", "ref-replace", null, null).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("www.example.com.", 300, "1.2.3.4"))), null);

        service.changeResourceRecordSets(zoneId, List.of(
                change("DELETE", aRecord("www.example.com.", 300, "1.2.3.4")),
                change("CREATE", aRecord("www.example.com.", 300, "5.6.7.8"))
        ), null);

        List<ResourceRecordSet> records = service.listResourceRecordSets(zoneId, "www.example.com.", "A", 1);
        assertThat(records, hasSize(1));
        assertEquals("5.6.7.8", records.get(0).getRecords().get(0).getValue());
    }

    @Test
    void clear_resetsPrivateZoneNameCounts() {
        Route53Service service = newService();
        String zoneId = service.createHostedZone("corp.internal.", "ref-clear", null,
                new VpcAssociation("vpc-123", "us-east-1")).zone().getId();

        service.changeResourceRecordSets(zoneId,
                List.of(change("CREATE", aRecord("app.corp.internal.", 300, "10.0.0.1"))), null);

        List<ResourceRecordSet> beforeClear = service.findPrivateRecordsForName("app.corp.internal.");
        assertThat(beforeClear, hasSize(1));

        service.clear();

        List<ResourceRecordSet> afterClear = service.findPrivateRecordsForName("app.corp.internal.");
        assertThat(afterClear, empty());
    }

    @Test
    void isCoveredByPrivateZone_matchesPrivateZoneDomainsOnly() {
        Route53Service service = newService();
        service.createHostedZone("corp.internal.", "ref-private", null,
                new VpcAssociation("vpc-123", "us-east-1"));
        service.createHostedZone("public.com.", "ref-public", null, null);

        assertTrue(service.isCoveredByPrivateZone("corp.internal"));
        assertTrue(service.isCoveredByPrivateZone("app.corp.internal."));
        assertTrue(service.isCoveredByPrivateZone("sub.service.corp.internal"));
        assertFalse(service.isCoveredByPrivateZone("public.com"));
        assertFalse(service.isCoveredByPrivateZone("othercorp.internal"));
        assertFalse(service.isCoveredByPrivateZone("example.org"));
    }

    private static HealthCheckConfig httpsCheck(String path) {
        HealthCheckConfig cfg = new HealthCheckConfig();
        cfg.setType("HTTPS");
        cfg.setFullyQualifiedDomainName("example.com");
        cfg.setPort(443);
        cfg.setResourcePath(path);
        cfg.setRequestInterval(30);
        cfg.setFailureThreshold(3);
        return cfg;
    }

    // Catches: an SDK retry of CreateHealthCheck (same caller reference, same settings) refused with
    // HealthCheckAlreadyExists, which fails a Terraform or OpenTofu apply.
    @Test
    void createHealthCheck_retryWithSameReferenceAndSettings_returnsTheExistingHealthCheck() {
        Route53Service service = newService();
        HealthCheck first = service.createHealthCheck("tf-ref-1", httpsCheck("/health"), "ResourcePath=/health");

        HealthCheck retried = service.createHealthCheck("tf-ref-1", httpsCheck("/health"), "ResourcePath=/health");

        assertEquals(first.getId(), retried.getId());
        assertThat(service.listHealthChecks(null, 0), hasSize(1));
    }

    @Test
    void createHealthCheck_sameReferenceWithDifferentSettings_isHealthCheckAlreadyExists() {
        Route53Service service = newService();
        service.createHealthCheck("tf-ref-2", httpsCheck("/health"), "ResourcePath=/health");

        AwsException e = assertThrows(AwsException.class,
                () -> service.createHealthCheck("tf-ref-2", httpsCheck("/other"), "ResourcePath=/other"));

        assertEquals("HealthCheckAlreadyExists", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }
}
