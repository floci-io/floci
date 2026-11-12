package io.floci.aws.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddedDnsAliasTargetTest {
    private static final String TARGET = "owned.example.test";

    @Test
    void anUnclaimedAliasTargetHasNoSuffixOrRecursiveFallback() {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of("example.test"), List.of());
        assertEquals(Optional.empty(), dns.resolveAliasTarget(TARGET, "ZOWNED", 1));
    }

    @Test
    void aUniqueResourceRetainsItsAddressAndTtl() {
        DnsAnswer answer = DnsAnswer.records(List.of("192.0.2.10"), 37);
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(source(Optional.empty()),
                source(Optional.of(answer))));
        assertEquals(Optional.of(answer), dns.resolveAliasTarget(TARGET, "ZOWNED", 1));
    }

    @Test
    void twoResourceClaimsAreRefusedEvenIfOneHasNoAddress() {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(source(Optional.of(DnsAnswer.noData())),
                source(Optional.of(DnsAnswer.records(List.of("192.0.2.10"), 37)))));
        assertEquals(Optional.of(DnsAnswer.refused()), dns.resolveAliasTarget(TARGET, "ZOWNED", 1));
    }

    @Test
    void aBrokenResourceSourceCannotFallThroughToAnotherOwner() {
        DnsRecordSource broken = mock(DnsRecordSource.class);
        DnsRecordSource next = mock(DnsRecordSource.class);
        when(broken.resolveAliasTarget(TARGET, "ZOWNED", 1)).thenThrow(new IllegalStateException("unavailable"));
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(broken, next));
        assertEquals(Optional.of(DnsAnswer.refused()), dns.resolveAliasTarget(TARGET, "ZOWNED", 1));
        verifyNoInteractions(next);
    }

    private static DnsRecordSource source(Optional<DnsAnswer> answer) {
        DnsRecordSource source = mock(DnsRecordSource.class);
        when(source.resolveAliasTarget(TARGET, "ZOWNED", 1)).thenReturn(answer);
        return source;
    }
}
