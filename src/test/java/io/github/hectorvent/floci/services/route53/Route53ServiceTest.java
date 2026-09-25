package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53ServiceTest {

    private static final String ACCOUNT = "000000000000";

    private Route53Service newService() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Route53ServiceConfig route53Config =
                Mockito.mock(EmulatorConfig.Route53ServiceConfig.class);

        Mockito.when(config.defaultAccountId()).thenReturn(ACCOUNT);
        Mockito.when(config.services()).thenReturn(servicesConfig);
        Mockito.when(servicesConfig.route53()).thenReturn(route53Config);
        Mockito.when(route53Config.defaultNameserver1()).thenReturn("ns-1.awsdns-00.com.");
        Mockito.when(route53Config.defaultNameserver2()).thenReturn("ns-2.awsdns-00.net.");
        Mockito.when(route53Config.defaultNameserver3()).thenReturn("ns-3.awsdns-00.org.");
        Mockito.when(route53Config.defaultNameserver4()).thenReturn("ns-4.awsdns-00.co.uk.");
        Mockito.when(route53Config.vpcAssociationControlPlaneDelayMs()).thenReturn(0L);

        Ec2Service ec2Service = Mockito.mock(Ec2Service.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", ACCOUNT);

        return new Route53Service(storageFactory, config, regionResolver, ec2Service);
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
}
