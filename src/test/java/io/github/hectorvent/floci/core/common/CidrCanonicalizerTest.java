package io.github.hectorvent.floci.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CidrCanonicalizerTest {

    // -----------------------------------------------------------------
    // Pinned cases from the bead — exact strings, not just equality.
    // -----------------------------------------------------------------

    @Test
    void awsDocumentedExample_rewritesHostBitsToZero() {
        assertEquals(Optional.of("100.68.0.0/18"), CidrCanonicalizer.canonicalize("100.68.0.18/18"));
    }

    @Test
    void alreadyCanonicalIpv4_isIdentityNotJustEqual() {
        String input = "10.0.0.0/16";
        Optional<String> result = CidrCanonicalizer.canonicalize(input);
        assertEquals(Optional.of("10.0.0.0/16"), result);
        // Must be the identical string value, not merely equal.
        assertSame(input, result.orElseThrow());
    }

    @Test
    void defaultRouteIpv4_zeroPrefixDoesNotDivideByZeroOrShiftBy32() {
        String input = "0.0.0.0/0";
        Optional<String> result = CidrCanonicalizer.canonicalize(input);
        assertEquals(Optional.of("0.0.0.0/0"), result);
        assertSame(input, result.orElseThrow());
    }

    @Test
    void hostRouteIpv4_slash32IsUnchanged() {
        String input = "10.1.2.3/32";
        Optional<String> result = CidrCanonicalizer.canonicalize(input);
        assertEquals(Optional.of("10.1.2.3/32"), result);
        assertSame(input, result.orElseThrow());
    }

    @Test
    void ipv6_rewritesHostBitsToZero() {
        assertEquals(Optional.of("2001:db8::/32"), CidrCanonicalizer.canonicalize("2001:db8::1/32"));
    }

    @Test
    void defaultRouteIpv6_zeroPrefixIsUnchanged() {
        String input = "::/0";
        Optional<String> result = CidrCanonicalizer.canonicalize(input);
        assertEquals(Optional.of("::/0"), result);
        assertSame(input, result.orElseThrow());
    }

    @Test
    void ipv6_expandedInputCanonicalizesToCompressedForm() {
        // AWS itself returns compressed IPv6; an expanded round-trip would be a diff.
        assertEquals(Optional.of("2001:db8::/48"), CidrCanonicalizer.canonicalize("2001:0db8:0000::/48"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not-a-cidr",
                "10.0.0.0",
                "10.0.0.0/",
                "/24",
                "10.0.0.0/-1",
                "10.0.0.0/33",
                "2001:db8::1/129",
                "2001:db8::1/-1",
                "999.0.0.0/24",
                "10.0.0.256/24",
                "10.0.0.0/abc",
                "example.com/24",
                "10.0.0.0/24/extra",
            })
    void malformedOrOutOfRangeCidr_isDistinguishablyInvalid(String input) {
        assertEquals(Optional.empty(), CidrCanonicalizer.canonicalize(input));
        assertFalse(CidrCanonicalizer.isCanonical(input));
    }

    @Test
    void nullCidr_isDistinguishablyInvalid() {
        assertEquals(Optional.empty(), CidrCanonicalizer.canonicalize(null));
        assertFalse(CidrCanonicalizer.isCanonical(null));
    }

    // -----------------------------------------------------------------
    // Additional coverage.
    // -----------------------------------------------------------------

    @Test
    void ipv6HostRoute_slash128IsUnchanged() {
        String input = "2001:db8::1/128";
        Optional<String> result = CidrCanonicalizer.canonicalize(input);
        assertEquals(Optional.of("2001:db8::1/128"), result);
        assertSame(input, result.orElseThrow());
    }

    @Test
    void ipv4MappedIpv6Literal_ofLiteralCollapsesItToIpv4_soPrefixIsBoundedByThirtyTwo() {
        // InetAddress.ofLiteral("::ffff:a.b.c.d") returns an Inet4Address, not an
        // Inet6Address — Java itself collapses the IPv4-mapped form. A prefix that
        // would be valid for the 128-bit IPv6 reading (e.g. /120) is therefore out of
        // range once resolved, and must be rejected rather than silently accepted
        // against the wrong bit width.
        assertEquals(Optional.empty(), CidrCanonicalizer.canonicalize("::ffff:192.168.1.1/120"));

        // A prefix within IPv4's 0-32 range works, canonicalizing as plain IPv4.
        Optional<String> result = CidrCanonicalizer.canonicalize("::ffff:192.168.1.1/24");
        assertEquals(Optional.of("192.168.1.0/24"), result);
        // Re-canonicalizing the output must be a no-op (idempotence).
        assertEquals(result, CidrCanonicalizer.canonicalize(result.orElseThrow()));
    }

    @ParameterizedTest
    @CsvSource({
        "100.68.0.18/18, 100.68.0.0/18",
        "10.0.0.0/16, 10.0.0.0/16",
        "0.0.0.0/0, 0.0.0.0/0",
        "10.1.2.3/32, 10.1.2.3/32",
        "2001:db8::1/32, 2001:db8::/32",
        "::/0, ::/0",
        "2001:0db8:0000::/48, 2001:db8::/48",
        "192.168.1.255/24, 192.168.1.0/24",
        "172.16.5.5/22, 172.16.4.0/22",
        "255.255.255.255/32, 255.255.255.255/32",
        "2001:db8:abcd:0012:0000:0000:0000:0001/64, 2001:db8:abcd:12::/64",
    })
    void canonicalize_matchesExpected(String input, String expected) {
        assertEquals(Optional.of(expected), CidrCanonicalizer.canonicalize(input));
    }

    @Test
    void isCanonical_trueOnlyForAlreadyCanonicalCidr() {
        assertTrue(CidrCanonicalizer.isCanonical("10.0.0.0/16"));
        assertTrue(CidrCanonicalizer.isCanonical("0.0.0.0/0"));
        assertTrue(CidrCanonicalizer.isCanonical("2001:db8::/32"));
        assertFalse(CidrCanonicalizer.isCanonical("100.68.0.18/18"));
        assertFalse(CidrCanonicalizer.isCanonical("2001:0db8:0000::/48"));
    }

    @Test
    void prefixLengthZero_forBothFamilies_isAccepted() {
        assertEquals(Optional.of("0.0.0.0/0"), CidrCanonicalizer.canonicalize("192.168.1.1/0"));
        assertEquals(Optional.of("::/0"), CidrCanonicalizer.canonicalize("2001:db8::1/0"));
    }

    @Test
    void whitespaceOnlyCidr_isDistinguishablyInvalid() {
        assertEquals(Optional.empty(), CidrCanonicalizer.canonicalize("   "));
        assertFalse(CidrCanonicalizer.isCanonical("   "));
    }
}
