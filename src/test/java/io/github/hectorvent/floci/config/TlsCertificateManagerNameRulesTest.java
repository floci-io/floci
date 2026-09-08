package io.github.hectorvent.floci.config;

import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which names {@code ensureHost} accepts: host name syntax, the one-label wildcard rule that
 * X.509 name matching and API Gateway custom domains share, and the local-suffix allow-list.
 */
class TlsCertificateManagerNameRulesTest extends TlsCertificateManagerFixture {

    @Test
    void knownOrWildcardCoveredHostIsANoOp() throws Exception {
        byte[] certBefore = servedCertificate();

        TlsCertificateManager m = manager();
        m.ensureHost("localhost");
        m.ensureHost("LOCALHOST.floci.io.");
        m.ensureHost("one-label.localhost.floci.io");
        m.ensureHost(" 127.0.0.1 ");

        assertArrayEquals(certBefore, servedCertificate());
        verify(defaultTls, never()).reload();
        verify(events, never()).fire(any());
    }

    @Test
    void aWildcardCoversExactlyOneLabel() throws Exception {
        TlsCertificateManager m = manager();

        m.ensureHost("one.localhost.floci.io");
        verify(defaultTls, never()).reload();

        m.ensureHost("two.labels.localhost.floci.io");
        verify(defaultTls, times(1)).reload();

        m.ensureHost("other.labels.localhost.floci.io");
        verify(defaultTls, times(2)).reload();
        assertTrue(sans(read("floci-server.crt")).contains("*.localhost.floci.io"), "the wildcard itself is kept");
    }

    @Test
    void wildcardIsAcceptedOnlyAsAWholeLeftmostLabel() throws Exception {
        TlsCertificateManager m = manager();

        m.ensureHost("*.api.example.localhost.floci.io");
        assertTrue(sans(read("floci-server.crt")).contains("*.api.example.localhost.floci.io"),
                "one leading *. label is a valid SAN");
        verify(defaultTls, times(1)).reload();

        m.ensureHost("*");
        m.ensureHost("*.");
        m.ensureHost("api*.example.localhost.floci.io");
        m.ensureHost("*.*.example.localhost.floci.io");
        m.ensureHost("example.*.localhost.floci.io");
        verify(defaultTls, times(1)).reload();
        Set<String> starred = new TreeSet<>(sans(read("floci-server.crt")));
        starred.removeIf(n -> !n.contains("*"));
        assertEquals(Set.of("*.localhost.floci.io", "*.api.example.localhost.floci.io"), starred,
                "malformed wildcards must not reach the certificate");
    }

    @Test
    void namesThatAreNotHostnamesAreRefused() throws Exception {
        byte[] certBefore = servedCertificate();

        TlsCertificateManager m = manager();
        m.ensureHost(null);
        m.ensureHost("");
        m.ensureHost("   ");
        m.ensureHost(".");
        m.ensureHost("api.example.localhost.floci.io:443");
        m.ensureHost("https://api.example.localhost.floci.io");
        m.ensureHost("api example.localhost.floci.io");
        m.ensureHost("api_1.example.localhost.floci.io");
        m.ensureHost("api..example.localhost.floci.io");
        m.ensureHost(".api.example.localhost.floci.io");
        m.ensureHost("-api.example.localhost.floci.io");
        m.ensureHost("api-.example.localhost.floci.io");
        m.ensureHost("a".repeat(64) + ".localhost.floci.io");
        m.ensureHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".localhost.floci.io");

        assertArrayEquals(certBefore, servedCertificate());
        verify(defaultTls, never()).reload();
        verify(events, never()).fire(any());
    }

    @Test
    void hostOutsideTheAllowListIsRefused() throws Exception {
        byte[] certBefore = servedCertificate();

        TlsCertificateManager m = manager();
        m.ensureHost("accounts.google.com");
        m.ensureHost("evil-localhost.floci.io");
        m.ensureHost("localhost.floci.io.attacker.example");
        m.ensureHost("floci.example");
        m.ensureHost("*.google.com");

        assertArrayEquals(certBefore, servedCertificate());
        verify(defaultTls, never()).reload();
        assertEquals(List.of(), readMetadata().getLearnedHostnames());
    }

    @Test
    void allowListIncludesConfiguredHostnameBaseUrlAndExtraSuffixes() throws Exception {
        when(config.baseUrl()).thenReturn("https://Floci.Corp.Example:4566");
        TlsCertificateManager m = manager();

        m.ensureHost("api.floci");
        m.ensureHost("floci");
        m.ensureHost("iot.example.internal");
        m.ensureHost("data.localhost.localstack.cloud");
        m.ensureHost("api.floci.corp.example");

        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.containsAll(List.of("api.floci", "floci", "iot.example.internal",
                "data.localhost.localstack.cloud", "api.floci.corp.example")), sans.toString());
    }

    @Test
    void unusableBaseUrlAndAbsentOptionalConfigStillAllowBuiltinSuffixes() throws Exception {
        when(config.baseUrl()).thenReturn("not a url");
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.dns().extraSuffixes()).thenReturn(Optional.empty());

        manager().ensureHost(NEW_HOST);

        assertTrue(sans(read("floci-server.crt")).contains(NEW_HOST));
    }

    @Test
    void anIpAddressAllowedByTheBaseUrlBecomesAnIpSan() throws Exception {
        when(config.baseUrl()).thenReturn("https://192.168.1.100:4566");

        manager().ensureHost("192.168.1.100");

        boolean ipSan = false;
        for (List<?> entry : read("floci-server.crt").getSubjectAlternativeNames()) {
            if (entry.get(0).equals(GeneralName.iPAddress) && "192.168.1.100".equals(entry.get(1))) {
                ipSan = true;
            }
        }
        assertTrue(ipSan, "an IP is encoded as an iPAddress SAN, not a dNSName");
    }
}
