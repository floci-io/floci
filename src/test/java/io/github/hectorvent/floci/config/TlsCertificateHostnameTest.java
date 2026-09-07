package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TLS certificate hostname functionality.
 * 
 * Tests that the TLS certificate generation correctly:
 * - Includes custom hostnames from FLOCI_HOSTNAME and FLOCI_BASE_URL in certificate SANs
 * - Regenerates certificates when hostname configuration changes
 * - Preserves default behavior for standard configurations
 * - Handles user-provided certificates correctly
 * - Manages certificate reuse and regeneration appropriately
 */
class TlsCertificateHostnameTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @AfterEach
    void cleanupSystemProperties() {
        System.clearProperty("floci.hostname");
        System.clearProperty("floci.base-url");
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.tls.cert-path");
        System.clearProperty("floci.tls.key-path");
        System.clearProperty("floci.storage.persistent-path");
    }

    // ==================== Custom Hostname Tests ====================

    @Test
    void testFlociHostnameIncludedInCertificateSans() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        assertTrue(sans.contains("floci"), 
            "Certificate SANs should contain 'floci' from FLOCI_HOSTNAME. Found: " + sans);
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    @Test
    void testBaseUrlHostnameIncludedInCertificateSans() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://myhost:4566");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        assertTrue(sans.contains("myhost"), 
            "Certificate SANs should contain 'myhost' from FLOCI_BASE_URL. Found: " + sans);
    }

    @Test
    void testBaseUrlIpAddressIncludedInCertificateSans() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://192.168.1.100:4566");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        assertTrue(sans.contains("192.168.1.100"), 
            "Certificate SANs should contain IP address from FLOCI_BASE_URL. Found: " + sans);
    }

    @Test
    void testBothHostnamesIncludedInCertificateSans() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "newhost");
        System.setProperty("floci.base-url", "http://oldhost:4566");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        assertTrue(sans.contains("newhost"), 
            "Certificate SANs should contain 'newhost' from FLOCI_HOSTNAME. Found: " + sans);
        assertTrue(sans.contains("oldhost"), 
            "Certificate SANs should contain 'oldhost' from FLOCI_BASE_URL. Found: " + sans);
    }

    // ==================== Certificate Regeneration Tests ====================

    @Test
    void testConfigurationChangeTriggersRegeneration() throws Exception {
        // Arrange: Generate initial certificate
        System.setProperty("floci.hostname", "host1");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate initialCert = parseCertificate(certFile);
        List<String> initialSans = extractSansFromCertificate(initialCert);
        assertTrue(initialSans.contains("host1"), "Initial certificate should contain 'host1'");

        // Act: Change hostname and restart
        System.setProperty("floci.hostname", "host2");
        new TlsConfigSource();

        // Assert: Certificate regenerated with new hostname
        X509Certificate regeneratedCert = parseCertificate(certFile);
        List<String> regeneratedSans = extractSansFromCertificate(regeneratedCert);
        
        assertTrue(regeneratedSans.contains("host2"), 
            "Certificate should contain 'host2' after configuration change. Found: " + regeneratedSans);
        assertFalse(regeneratedSans.contains("host1"), 
            "Certificate should not contain old hostname 'host1'");
    }

    @Test
    void testMissingMetadataTriggersRegeneration() throws Exception {
        // Arrange: Create certificate without metadata (simulating old version)
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        Path certFile = tlsDir.resolve("floci-server.crt");
        Path keyFile = tlsDir.resolve("floci-server.key");
        Path metadataFile = tlsDir.resolve("floci-server.metadata.json");

        // Generate certificate without metadata
        CertificateGenerator gen = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = gen.generateSelfSignedCertificate(
            "localhost", 
            List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost", "localhost.floci.io", "*.localhost.floci.io"), 
            KeyAlgorithm.RSA_2048);
        Files.writeString(certFile, generated.certificatePem());
        Files.writeString(keyFile, generated.privateKeyPem());

        assertFalse(Files.exists(metadataFile), "Metadata should not exist initially");

        // Act: Trigger TlsConfigSource
        new TlsConfigSource();

        // Assert: Metadata created and certificate regenerated with custom hostname
        assertTrue(Files.exists(metadataFile), 
            "Metadata file should exist after regeneration");
        
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        assertTrue(sans.contains("floci"), 
            "Regenerated certificate should contain 'floci'. Found: " + sans);
    }

    // ==================== Default Configuration Tests ====================

    @Test
    void testDefaultConfigurationGeneratesDefaultSans() throws Exception {
        // Arrange: Default configuration (no custom hostnames)
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        Set<String> expectedSans = Set.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io",
                "*.execute-api.localhost.floci.io",
                "*.execute-api.localhost.localstack.cloud", "host.docker.internal");
        Set<String> actualSans = new HashSet<>(sans);
        
        assertEquals(expectedSans, actualSans,
            "Default configuration should generate certificate with default SANs only");
    }

    @ParameterizedTest
    @MethodSource("defaultConfigurations")
    void testDefaultConfigurationsProduceDefaultSans(Map<String, String> config) throws Exception {
        // Arrange
        config.forEach(System::setProperty);
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        Set<String> expectedSans = Set.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io",
                "*.execute-api.localhost.floci.io",
                "*.execute-api.localhost.localstack.cloud", "host.docker.internal");
        Set<String> actualSans = new HashSet<>(sans);
        
        assertEquals(expectedSans, actualSans,
            "Default configuration should produce default SANs. Config: " + config);
        
        // Cleanup
        config.keySet().forEach(System::clearProperty);
    }

    // ==================== User-Provided Certificate Tests ====================

    @Test
    void testUserProvidedCertificatesUsedWithoutModification() throws Exception {
        // Arrange: Create user-provided certificate
        Path userCertFile = tempDir.resolve("user-cert.crt");
        Path userKeyFile = tempDir.resolve("user-key.key");
        
        CertificateGenerator gen = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate userCert = gen.generateSelfSignedCertificate(
            "user-domain.com",
            List.of("user-domain.com", "*.user-domain.com"),
            KeyAlgorithm.RSA_2048);
        Files.writeString(userCertFile, userCert.certificatePem());
        Files.writeString(userKeyFile, userCert.privateKeyPem());

        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCertFile.toString());
        System.setProperty("floci.tls.key-path", userKeyFile.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert: No self-signed certificate generated
        Path selfSignedCert = tempDir.resolve("tls/floci-server.crt");
        assertFalse(Files.exists(selfSignedCert), 
            "Self-signed certificate should not be generated when user provides certificates");
    }

    // ==================== TLS Disabled Tests ====================

    @Test
    void testTlsDisabledSkipsCertificateGeneration() throws Exception {
        // Arrange
        System.setProperty("floci.tls.enabled", "false");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act
        new TlsConfigSource();

        // Assert
        Path tlsDir = tempDir.resolve("tls");
        if (Files.exists(tlsDir)) {
            assertFalse(Files.exists(tlsDir.resolve("floci-server.crt")),
                "No certificate should be created when TLS is disabled");
        }
    }

    // ==================== Certificate Reuse Tests ====================

    @Test
    void testUnchangedConfigurationReusesCertificate() throws Exception {
        // Arrange: Generate initial certificate
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-server.crt");
        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String initialMetadata = Files.readString(tempDir.resolve("tls/floci-server.metadata.json"));

        Thread.sleep(100);

        // Act: Restart with same configuration
        new TlsConfigSource();

        // Assert: Certificate reused (not regenerated)
        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String newMetadata = Files.readString(tempDir.resolve("tls/floci-server.metadata.json"));
        
        assertEquals(initialModifiedTime, newModifiedTime, 
            "Certificate should be reused when configuration unchanged");
        assertEquals(initialMetadata, newMetadata, 
            "Metadata should be unchanged");
    }

    // ==================== Local CA Tests ====================

    @Test
    void serverCertificateIsIssuedByTheLocalCa() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        TlsConfigSource source = new TlsConfigSource();

        Path tlsDir = tempDir.resolve("tls");
        X509Certificate ca = parseCertificate(tlsDir.resolve("floci-root-ca.crt"));
        X509Certificate leaf = parseCertificate(tlsDir.resolve("floci-server.crt"));
        leaf.verify(ca.getPublicKey());
        assertEquals(ca.getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertEquals(-1, leaf.getBasicConstraints(), "server leaf must not be a CA");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), leaf.getExtendedKeyUsage());

        assertEquals(tlsDir.resolve("floci-server.crt").toAbsolutePath().toString(),
                source.getValue("quarkus.tls.key-store.pem.0.cert"));
        assertEquals(tlsDir.resolve("floci-server.key").toAbsolutePath().toString(),
                source.getValue("quarkus.tls.key-store.pem.0.key"));
        assertNull(source.getValue("quarkus.http.ssl.certificate.files"), "legacy key no longer published");
        assertNull(source.getValue("quarkus.http.ssl.certificate.key-files"), "legacy key no longer published");
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(tlsDir.resolve("floci-server.key")));
        assertEquals(tlsDir, TlsConfigSource.resolvedTlsDir());
    }

    @Test
    void resolvedTlsDirFollowsEveryTlsOnBootAndIsClearedWhenTlsIsOff() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        new TlsConfigSource();
        assertEquals(tempDir.resolve("tls"), TlsConfigSource.resolvedTlsDir());

        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        assertNull(TlsConfigSource.resolvedTlsDir(), "TLS off: nothing was laid down");

        Path userCert = tempDir.resolve("user.crt");
        Path userKey = tempDir.resolve("user.key");
        var user = new CertificateGenerator().generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Files.writeString(userCert, user.certificatePem());
        Files.writeString(userKey, user.privateKeyPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        new TlsConfigSource();
        assertEquals(tempDir.resolve("tls"), TlsConfigSource.resolvedTlsDir(),
                "user certificate: the container CA bundle was laid down there");
    }

    @Test
    void legacySelfSignedLeafIsReplacedByCaIssuedOne() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        CertificateGenerator gen = new CertificateGenerator();
        // The exact SAN list TlsConfigSource would compute for this configuration, so only the
        // issuer check can trigger regeneration here.
        List<String> sans = List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io", "*.execute-api.localhost.floci.io",
                "*.execute-api.localhost.localstack.cloud", "host.docker.internal");
        var legacy = gen.generateSelfSignedCertificate("localhost", sans, KeyAlgorithm.RSA_2048);
        Files.writeString(tlsDir.resolve("floci-server.crt"), legacy.certificatePem());
        Files.writeString(tlsDir.resolve("floci-server.key"), legacy.privateKeyPem());
        Files.writeString(tlsDir.resolve("floci-server.metadata.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        CertificateMetadata.create(sans, "dev")));

        new TlsConfigSource();

        X509Certificate ca = parseCertificate(tlsDir.resolve("floci-root-ca.crt"));
        X509Certificate leaf = parseCertificate(tlsDir.resolve("floci-server.crt"));
        assertEquals(ca.getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertNotEquals(legacy.certificatePem(), Files.readString(tlsDir.resolve("floci-server.crt")));
    }

    @Test
    void expiredServerLeafIsReissued() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        CertificateGenerator gen = new CertificateGenerator();
        List<String> sans = List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io", "*.execute-api.localhost.floci.io",
                "*.execute-api.localhost.localstack.cloud", "host.docker.internal");
        java.security.KeyPair keyPair = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
        // Issued by the current CA with a validity that ended a day ago: only the expiry check can trigger.
        X509Certificate expired = gen.signCertificate(new org.bouncycastle.asn1.x500.X500Name("CN=localhost"),
                keyPair.getPublic(), org.bouncycastle.asn1.x500.X500Name.getInstance(
                        ca.certificate().getSubjectX500Principal().getEncoded()), ca.key(), sans, false,
                CertificateGenerator.LeafUsage.SERVER, -1);
        Files.writeString(tlsDir.resolve("floci-server.crt"), gen.toPem(expired));
        Files.writeString(tlsDir.resolve("floci-server.key"), gen.toPem(keyPair.getPrivate()));
        Files.writeString(tlsDir.resolve("floci-server.metadata.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(CertificateMetadata.create(sans, "dev")));
        assertTrue(ca.isIssuedByUs(expired), "the expired leaf is ours, so only validity can reject it");

        new TlsConfigSource();

        X509Certificate leaf = parseCertificate(tlsDir.resolve("floci-server.crt"));
        leaf.checkValidity();
        assertNotEquals(expired.getSerialNumber(), leaf.getSerialNumber());
        assertTrue(ca.isIssuedByUs(leaf));
    }

    @Test
    void leafSignedByAPreviousCaIsReplacedWhenTheCaChanges() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        new TlsConfigSource();
        Path tlsDir = tempDir.resolve("tls");
        String firstLeaf = Files.readString(tlsDir.resolve("floci-server.crt"));
        Files.delete(tlsDir.resolve("floci-root-ca.key"));

        new TlsConfigSource();

        X509Certificate ca = parseCertificate(tlsDir.resolve("floci-root-ca.crt"));
        X509Certificate leaf = parseCertificate(tlsDir.resolve("floci-server.crt"));
        assertNotEquals(firstLeaf, Files.readString(tlsDir.resolve("floci-server.crt")));
        leaf.verify(ca.getPublicKey());
    }

    // ==================== Helper Methods ====================

    private X509Certificate parseCertificate(Path certFile) throws Exception {
        String certPem = Files.readString(certFile);
        CertificateGenerator gen = new CertificateGenerator();
        return gen.parseCertificate(certPem);
    }

    private List<String> extractSansFromCertificate(X509Certificate cert) throws Exception {
        List<String> sans = new ArrayList<>();
        
        Collection<List<?>> subjectAltNames = cert.getSubjectAlternativeNames();
        if (subjectAltNames != null) {
            for (List<?> san : subjectAltNames) {
                Integer type = (Integer) san.get(0);
                String value = (String) san.get(1);
                
                if (type == GeneralName.dNSName || type == GeneralName.iPAddress) {
                    sans.add(value);
                }
            }
        }
        
        return sans;
    }

    static Stream<Map<String, String>> defaultConfigurations() {
        return Stream.of(
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true"),
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true", 
                   "floci.base-url", "http://localhost:4566"),
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true", 
                   "floci.base-url", "http://127.0.0.1:4566"),
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true", 
                   "floci.hostname", "localhost"),
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true", 
                   "floci.base-url", "https://localhost:4566"),
            Map.of("floci.tls.enabled", "true", "floci.tls.self-signed", "true", 
                   "floci.base-url", "http://0.0.0.0:4566")
        );
    }
}
