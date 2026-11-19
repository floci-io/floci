package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2DnsRecordSourceTest {
    private static final String NAME = "owned.elb.example.test";
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final ElbV2DnsRecordSource source = new ElbV2DnsRecordSource(service);

    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void aValidatedApplicationBalancerUsesItsListenerAddress(String scheme) {
        LoadBalancer target = balancer("application");
        target.setScheme(scheme);
        when(service.findByDnsName(NAME)).thenReturn(List.of(target));
        assertEquals(Optional.of(DnsAnswer.records(List.of("192.0.2.10"), 60)),
                source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "gateway"})
    void unsupportedBalancerTypesRemainRefused(String type) {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer(type)));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @Test
    void mismatchedOwnershipAndUnsupportedQueryTypesCannotReachTheListener() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "WRONG", 1, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, null, 1, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 16, "192.0.2.10"));
    }

    @Test
    void ambiguousResourcesAreRefused() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application"), balancer("application")));
        assertEquals(Optional.of(DnsAnswer.refused()), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
    }

    @Test
    void missingResourcesAreUnclaimedAndRawNamesAreNotClaimed() {
        when(service.findByDnsName(NAME)).thenReturn(List.of());
        assertEquals(Optional.empty(), source.resolveAliasTarget(NAME, "ZOWNED", 1, "192.0.2.10"));
        assertEquals(Optional.empty(), source.resolveIpv4(NAME));
    }

    @Test
    void validIpv6TargetsAndUnavailableListenerAddressesReturnNoData() {
        when(service.findByDnsName(NAME)).thenReturn(List.of(balancer("application")));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 28, "192.0.2.10"));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 1, null));
        assertEquals(Optional.of(DnsAnswer.noData()), source.resolveAliasTarget(NAME, "ZOWNED", 1, " "));
    }

    private static LoadBalancer balancer(String type) {
        LoadBalancer target = new LoadBalancer();
        target.setType(type);
        target.setCanonicalHostedZoneId("ZOWNED");
        return target;
    }
}
