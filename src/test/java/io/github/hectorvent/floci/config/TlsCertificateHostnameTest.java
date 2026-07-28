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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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

        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Path keyFile = tlsDir.resolve("floci-selfsigned.key");
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");

        // Generate certificate without metadata
        CertificateGenerator gen = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = gen.generateCertificate(
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        Set<String> expectedSans = Set.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io", "host.docker.internal");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        X509Certificate cert = parseCertificate(certFile);
        List<String> sans = extractSansFromCertificate(cert);
        
        Set<String> expectedSans = Set.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci.io", "*.localhost.floci.io", "host.docker.internal");
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
        CertificateGenerator.GeneratedCertificate userCert = gen.generateCertificate(
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
        Path selfSignedCert = tempDir.resolve("tls/floci-selfsigned.crt");
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
            assertFalse(Files.exists(tlsDir.resolve("floci-selfsigned.crt")),
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

        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String initialMetadata = Files.readString(tempDir.resolve("tls/floci-selfsigned.metadata.json"));

        Thread.sleep(100);

        // Act: Restart with same configuration
        new TlsConfigSource();

        // Assert: Certificate reused (not regenerated)
        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String newMetadata = Files.readString(tempDir.resolve("tls/floci-selfsigned.metadata.json"));
        
        assertEquals(initialModifiedTime, newModifiedTime, 
            "Certificate should be reused when configuration unchanged");
        assertEquals(initialMetadata, newMetadata, 
            "Metadata should be unchanged");
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
