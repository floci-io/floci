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
        var cert = generator.generateSelfSignedCertificate(
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
        var cert = generator.generateSelfSignedCertificate(
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
        var cert = generator.generateSelfSignedCertificate(
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
        var cert = generator.generateSelfSignedCertificate(
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
        var cert = generator.generateSelfSignedCertificate(
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

    // ==================== hex-shaped names are not addresses ====================

    /**
     * A hex-only label used to match the old IP pattern and be handed to
     * {@code InetAddress.getByName}, which decodes {@code 1234} as {@code 0.0.4.210}. The
     * certificate then carried an address nobody asked for, and the name it was given was not
     * covered at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1234", "0", "beef", "cafe", "abcd"})
    void hexShapedLabelsAreDnsNamesNotAddresses(String name) throws Exception {
        var cert = generator.generateSelfSignedCertificate(
                "localhost", List.of("localhost", name), KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        assertTrue(sans.stream().anyMatch(san -> (Integer) san.get(0) == GeneralName.dNSName
                        && san.get(1).toString().equals(name)),
                name + " must be covered as a dNSName. Found: " + sans);
        assertFalse(sans.stream().anyMatch(san -> (Integer) san.get(0) == GeneralName.iPAddress
                        && !san.get(1).toString().equals("127.0.0.1")),
                name + " must not become an iPAddress SAN. Found: " + sans);
    }

    /** An octet above 255 is not an address; the old dotted-quad pattern accepted it. */
    @ParameterizedTest
    @ValueSource(strings = {"256.1.1.1", "999.999.999.999", "1.2.3.4.5", "01.02.03.256"})
    void outOfRangeDottedQuadsAreDnsNames(String name) {
        assertFalse(CertificateGenerator.isIpAddress(name),
                name + " is not a valid IPv4 address");
    }

    /** A malformed IPv6 literal still reaches the DNS-name fallback rather than being dropped. */
    @Test
    void aMalformedIpv6LiteralIsStillCoveredAsADnsName() throws Exception {
        var cert = generator.generateSelfSignedCertificate(
                "localhost", List.of("localhost", "fffff::1"), KeyAlgorithm.RSA_2048);

        X509Certificate x509 = generator.parseCertificate(cert.certificatePem());
        Collection<List<?>> sans = x509.getSubjectAlternativeNames();

        assertTrue(sans.stream().anyMatch(san -> (Integer) san.get(0) == GeneralName.dNSName
                        && san.get(1).toString().equals("fffff::1")),
                "a name we cannot parse must still be covered, not silently dropped. Found: " + sans);
    }

    /**
     * The property that keeps certificate generation off the network: only values already known
     * to be literals are handed to the resolver. A colon-free label must never be treated as an
     * address, however hex-shaped, because resolving it would be a blocking DNS call whose result
     * would replace the name the caller asked to cover.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1234", "beef", "deadbeef", "localhost", "floci"})
    void colonFreeLabelsAreNeverTreatedAsAddresses(String name) {
        assertFalse(CertificateGenerator.isIpAddress(name),
                name + " has no colon and is not a dotted quad, so it must not be resolved");
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

    /**
     * A colon somewhere is not enough to make a value a literal, and treating it as one puts the
     * resolver back in the certificate path.
     *
     * <p>{@code InetAddress.getAllByName} only attempts a literal parse when the first character
     * is an ASCII hex digit or a colon; everything else it resolves. Measured on JDK 25,
     * {@code getByName("z:1")} takes tens of milliseconds and fails with the resolver's
     * "nodename nor servname provided", while {@code getByName("fffff::1")} fails in under a
     * millisecond with "invalid IPv6 address literal". Only the second never left the JVM.
     */
    @ParameterizedTest
    @ValueSource(strings = {"z:1", "host:8080", "xyz::1", "-:1", "_:1", "g::1"})
    void isIpAddress_colonButNotLiteralShaped_staysADnsName(String value) {
        assertFalse(CertificateGenerator.isIpAddress(value),
                value + " starts with a character the JDK will not read as a literal, so calling it "
                        + "an IP address would send it to the name service");
    }

    /**
     * The shape a {@code Character.digit(c, 16)} implementation would let through.
     *
     * <p>{@code IPAddressUtil.digit} is ASCII-only by default and its own comment gives the set as
     * [0-9,A-F,a-f], but {@code Character.digit} answers 1 for the Arabic-Indic digit one. Writing
     * the leading-character test the convenient way would classify this as a literal, hand it to
     * the resolver, and have the JDK decline to parse it and look it up instead.
     */
    @Test
    void isIpAddress_nonAsciiDigitLeadingColonValue_staysADnsName() {
        assertFalse(CertificateGenerator.isIpAddress("\u0661:1"),
                "a non-ASCII digit is not one of [0-9,A-F,a-f], so the JDK would resolve this");
    }

    /** Literal-shaped leading characters still count, including uppercase hex and a bare colon. */
    @ParameterizedTest
    @ValueSource(strings = {"::1", "FE80::1", "fe80::1", "0::1", "9:1", "A::1", "f::1"})
    void isIpAddress_literalShapedLeadingCharacter_isStillAnIpAddress(String value) {
        assertTrue(CertificateGenerator.isIpAddress(value), value + " should be detected as IP");
    }
}
