package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2DnsRecordSourceTest {
    private static final String NAME = "owned.elb.example.test";
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
    private final ElbV2DnsRecordSource source = new ElbV2DnsRecordSource(service, dns);

    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void aValidatedApplicationBalancerUsesItsListenerAddress(String scheme) {
        LoadBalancer target = balancer("application");
        target.setScheme(scheme);
        when(service.findByDnsName(NAME)).thenReturn(List.of(target));
        when(dns.getServerIp()).thenReturn(Optional.of("192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.records(List.of("192.0.2.10"), 60)),
                source.resolveAliasTarget(NAME, "ZOWNED", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "gateway"})
    void unsupportedBalancerTypesRemainRefused(String type) {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer(type)));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1));
        verifyNoInteractions(dns);
    }

    @Test
    void mismatchedOwnershipAndUnsupportedQueryTypesCannotReachTheListener() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "WRONG", 1));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, null, 1));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 16));
        verifyNoInteractions(dns);
    }

    @Test
    void ambiguousResourcesAreRefused() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application"), balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1));
        verifyNoInteractions(dns);
    }

    @Test
    void missingResourcesAreUnclaimedAndRawNamesAreNotClaimed() {
        when(service.findByDnsName(NAME)).thenReturn(List.of());
        assertEquals(Optional.empty(), source.resolveAliasTarget(NAME, "ZOWNED", 1));
        assertEquals(Optional.empty(), source.resolveIpv4(NAME));
        verifyNoInteractions(dns);
    }

    @Test
    void validIpv6TargetsAndUnavailableListenerAddressesReturnNoData() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 28));
        verifyNoInteractions(dns);
        when(dns.getServerIp()).thenReturn(Optional.empty());
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 1));
    }

    private static LoadBalancer balancer(String type) {
        LoadBalancer target = new LoadBalancer();
        target.setType(type);
        target.setCanonicalHostedZoneId("ZOWNED");
        return target;
    }
}
