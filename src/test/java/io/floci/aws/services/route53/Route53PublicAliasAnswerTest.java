package io.floci.aws.services.route53;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsLookupHelper;
import io.floci.aws.core.common.dns.DnsRecord;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.route53.model.AliasTarget;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Route53PublicAliasAnswerTest {
    private static final String NAME = "api.example.test.";
    private final EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
    private final DnsLookupHelper lookup = mock(DnsLookupHelper.class);

    @ParameterizedTest
    @ValueSource(strings = {"identifier", "weight", "region", "failover", "healthCheck",
            "targetHealth", "multivalue", "ttl", "values", "type", "multiple"})
    void unsupportedOrMalformedStoredAliasesAreRefusedBeforeTargetLookup(String invalid) {
        ResourceRecordSet record = alias();
        int type = 1;
        switch (invalid) {
            case "identifier" -> record.setSetIdentifier("weighted");
            case "weight" -> record.setWeight(1L);
            case "region" -> record.setRegion("eu-west-1");
            case "failover" -> record.setFailover("PRIMARY");
            case "healthCheck" -> record.setHealthCheckId("health-check");
            case "targetHealth" -> record.getAliasTarget().setEvaluateTargetHealth(true);
            case "multivalue" -> record.setMultiValueAnswer(true);
            case "ttl" -> record.setTtl(60L);
            case "values" -> record.setRecords(List.of(new ResourceRecord("192.0.2.1")));
            case "type" -> {
                record.setType("TXT");
                type = 16;
            }
            case "multiple" -> record.getAliasTarget().setDnsName("other.example.test.");
            default -> throw new AssertionError(invalid);
        }
        List<ResourceRecordSet> records = "multiple".equals(invalid) ? List.of(record, alias()) : List.of(record);
        assertEquals(5, source(records).resolveAuthoritative(NAME, type).orElseThrow().responseCode());
        verifyNoInteractions(dns, lookup);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validTargetsRetainTheirTtlAndZoneAuthority(boolean explicitSimpleRecord) {
        when(dns.resolveAliasTarget("target.example.test.", "ZOWNED", 1))
                .thenReturn(Optional.of(DnsAnswer.records(List.of("192.0.2.20"), 73)));
        ResourceRecordSet record = alias();
        if (explicitSimpleRecord) {
            record.setMultiValueAnswer(false);
        }
        DnsAnswer answer = source(List.of(record)).resolveAuthoritative(NAME, 1).orElseThrow();
        assertEquals(List.of("192.0.2.20"), answer.addresses());
        assertEquals(73, answer.ttlSeconds());
        assertNotNull(answer.authority());
        verifyNoInteractions(lookup);
    }

    @Test
    void missingTargetsAndWrongAddressFamiliesRemainRefused() {
        when(dns.resolveAliasTarget("target.example.test.", "ZOWNED", 1)).thenReturn(Optional.empty());
        assertEquals(5, source(List.of(alias())).resolveAuthoritative(NAME, 1).orElseThrow().responseCode());
        when(dns.resolveAliasTarget("target.example.test.", "ZOWNED", 1)).thenReturn(
                Optional.of(DnsAnswer.typedRecords(List.of(new DnsRecord.Address(28, "2001:db8::1")), 60)));
        assertEquals(5, source(List.of(alias())).resolveAuthoritative(NAME, 1).orElseThrow().responseCode());
        verifyNoInteractions(lookup);
    }

    private Route53DnsRecordSource source(List<ResourceRecordSet> records) {
        Route53Service zones = mock(Route53Service.class);
        ResourceRecordSet soa = new ResourceRecordSet();
        soa.setName("example.test.");
        soa.setType("SOA");
        soa.setTtl(60L);
        soa.setRecords(List.of(new ResourceRecord("ns.example.test. hostmaster.example.test. 1 60 60 60 60")));
        when(zones.publicDnsZones(NAME)).thenReturn(
                List.of(new Route53Service.PublicDnsZone("example.test.", records, List.of(soa))));
        return new Route53DnsRecordSource(zones, null, lookup, dns);
    }

    private static ResourceRecordSet alias() {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(NAME);
        record.setType("A");
        AliasTarget target = new AliasTarget();
        target.setDnsName("target.example.test.");
        target.setHostedZoneId("ZOWNED");
        record.setAliasTarget(target);
        return record;
    }
}
