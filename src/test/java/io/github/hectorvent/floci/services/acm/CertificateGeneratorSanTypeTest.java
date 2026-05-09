package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that CertificateGenerator encodes SANs with the correct type:
 * - DNS names use GeneralName.dNSName (type 2)
 * - IP addresses use GeneralName.iPAddress (type 7)
 *
 * This is critical for TLS validation — clients only match IP addresses
 * against iPAddress SANs, not dNSName SANs (RFC 5280 §4.2.1.6).
 */
class CertificateGeneratorSanTypeTest {

    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @Test
    void ipv4AddressEncodedAsIpAddressSanType() throws Exception {
        var cert = generator.generateCertificate(
                "localhost",
                List.of("localhost", "192.168.1.100"),
                KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        // Find the 192.168.1.100 SAN and verify its type is iPAddress (7)
        boolean foundIpSan = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.iPAddress
                        && san.get(1).toString().equals("192.168.1.100"));

        assertTrue(foundIpSan,
                "192.168.1.100 should be encoded as iPAddress SAN (type 7). Found SANs: " + sans);
    }

    @Test
    void ipv6LoopbackEncodedAsIpAddressSanType() throws Exception {
        var cert = generator.generateCertificate(
                "localhost",
                List.of("localhost", "0.0.0.0", "::1"),
                KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        // 0.0.0.0 should be iPAddress type
        boolean foundZeroIp = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.iPAddress
                        && san.get(1).toString().equals("0.0.0.0"));
        assertTrue(foundZeroIp,
                "0.0.0.0 should be encoded as iPAddress SAN (type 7). Found SANs: " + sans);

        // ::1 should be iPAddress type (rendered as 0:0:0:0:0:0:0:1 by Java)
        boolean foundIpv6 = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.iPAddress
                        && san.get(1).toString().contains("0:0:0:0:0:0:0:1"));
        assertTrue(foundIpv6,
                "::1 should be encoded as iPAddress SAN (type 7). Found SANs: " + sans);
    }

    @Test
    void dnsNamesEncodedAsDnsNameSanType() throws Exception {
        var cert = generator.generateCertificate(
                "localhost",
                List.of("myhost.example.com", "floci"),
                KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        // myhost.example.com should be dNSName type
        boolean foundDns = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.dNSName
                        && san.get(1).toString().equals("myhost.example.com"));
        assertTrue(foundDns,
                "myhost.example.com should be encoded as dNSName SAN (type 2). Found SANs: " + sans);

        // floci should be dNSName type
        boolean foundFloci = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.dNSName
                        && san.get(1).toString().equals("floci"));
        assertTrue(foundFloci,
                "floci should be encoded as dNSName SAN (type 2). Found SANs: " + sans);
    }

    @Test
    void wildcardEncodedAsDnsNameSanType() throws Exception {
        var cert = generator.generateCertificate(
                "localhost",
                List.of("localhost", "*.localhost"),
                KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        boolean foundWildcard = sans.stream()
                .anyMatch(san -> (Integer) san.get(0) == GeneralName.dNSName
                        && san.get(1).toString().equals("*.localhost"));
        assertTrue(foundWildcard,
                "*.localhost should be encoded as dNSName SAN (type 2). Found SANs: " + sans);
    }

    @Test
    void mixedIpsAndDnsNamesEncodedCorrectly() throws Exception {
        var cert = generator.generateCertificate(
                "localhost",
                List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost", "floci", "10.0.0.5"),
                KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        // Count DNS vs IP SANs
        long dnsCount = sans.stream()
                .filter(san -> (Integer) san.get(0) == GeneralName.dNSName)
                .count();
        long ipCount = sans.stream()
                .filter(san -> (Integer) san.get(0) == GeneralName.iPAddress)
                .count();

        // DNS: localhost, *.localhost, floci = 3
        assertEquals(3, dnsCount,
                "Should have 3 dNSName SANs (localhost, *.localhost, floci). Found SANs: " + sans);
        // IP: 127.0.0.1, 0.0.0.0, 10.0.0.5 = 3
        assertEquals(3, ipCount,
                "Should have 3 iPAddress SANs (127.0.0.1, 0.0.0.0, 10.0.0.5). Found SANs: " + sans);
    }

    // ==================== isIpAddress utility tests ====================

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.1", "10.0.0.1", "127.0.0.1", "0.0.0.0", "255.255.255.255"})
    void isIpAddress_ipv4(String value) {
        assertTrue(CertificateGenerator.isIpAddress(value), value + " should be detected as IP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "fe80::1", "2001:db8::1", "[::1]", "[fe80::1]"})
    void isIpAddress_ipv6(String value) {
        assertTrue(CertificateGenerator.isIpAddress(value), value + " should be detected as IP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "floci", "myhost.example.com", "*.localhost", "a.b.c.d.e"})
    void isIpAddress_dnsNames(String value) {
        assertFalse(CertificateGenerator.isIpAddress(value), value + " should NOT be detected as IP");
    }

    @Test
    void isIpAddress_nullAndBlank() {
        assertFalse(CertificateGenerator.isIpAddress(null));
        assertFalse(CertificateGenerator.isIpAddress(""));
        assertFalse(CertificateGenerator.isIpAddress("   "));
    }
}
