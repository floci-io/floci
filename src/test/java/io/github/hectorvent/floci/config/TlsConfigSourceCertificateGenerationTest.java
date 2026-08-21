package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TLS certificate generation with custom hostnames.
 * 
 * Tests Task 3.5: Update certificate generation to include custom hostnames
 * - Verifies that extractCustomHostnames() is called
 * - Verifies that custom hostnames are combined with default SANs
 * - Verifies that the combined list is deduplicated
 * - Verifies that the combined SANs are passed to CertificateGenerator
 * - Verifies that logging shows custom hostnames when present
 */
class TlsConfigSourceCertificateGenerationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        // Enable TLS and self-signed mode
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
        System.clearProperty("floci.hostname");
        System.clearProperty("floci.base-url");
        System.clearProperty("floci.dns.spoof-aws-endpoints");
        System.clearProperty("floci.default-region");
    }

    /**
     * Test that certificate includes custom hostname from FLOCI_HOSTNAME
     */
    @Test
    void testCertificateIncludesFlociHostname() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "floci");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("floci"), 
            "Certificate SANs should include 'floci' from FLOCI_HOSTNAME");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate includes custom hostname from FLOCI_BASE_URL
     */
    @Test
    void testCertificateIncludesBaseUrlHostname() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("myhost"), 
            "Certificate SANs should include 'myhost' from FLOCI_BASE_URL");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate includes IP address from FLOCI_BASE_URL
     */
    @Test
    void testCertificateIncludesIpAddress() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://192.168.1.100:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("192.168.1.100"), 
            "Certificate SANs should include '192.168.1.100' from FLOCI_BASE_URL");
    }

    /**
     * Test that certificate includes both FLOCI_HOSTNAME and FLOCI_BASE_URL hostnames
     */
    @Test
    void testCertificateIncludesBothHostnames() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "newhost");
        System.setProperty("floci.base-url", "http://oldhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("newhost"), 
            "Certificate SANs should include 'newhost' from FLOCI_HOSTNAME");
        assertTrue(sans.contains("oldhost"), 
            "Certificate SANs should include 'oldhost' from FLOCI_BASE_URL");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate with default configuration includes only default SANs
     */
    @Test
    void testCertificateWithDefaultConfiguration() throws Exception {
        // Arrange - no custom hostnames
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
        assertTrue(sans.contains("127.0.0.1"), 
            "Certificate SANs should include default '127.0.0.1'");
        assertTrue(sans.contains("0.0.0.0"), 
            "Certificate SANs should include default '0.0.0.0'");
        
        assertTrue(sans.contains("host.docker.internal"),
            "Certificate SANs should include default 'host.docker.internal'");
        assertTrue(sans.contains("*.execute-api.localhost.floci.io"),
            "Certificate SANs should include API Gateway execution hosts");
        assertTrue(sans.contains("*.execute-api.localhost.localstack.cloud"),
            "Certificate SANs should include LocalStack-compatible API Gateway execution hosts");

        // Should not contain any custom hostnames
        assertEquals(9, sans.size(),
            "Certificate SANs should contain exactly 9 default entries, including API Gateway execution hosts");
    }

    /**
     * Test that duplicate hostnames are deduplicated
     */
    @Test
    void testDeduplicationInCertificate() throws Exception {
        // Arrange - same hostname in both sources
        System.setProperty("floci.hostname", "myhost");
        System.setProperty("floci.base-url", "http://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        long myhostCount = sans.stream().filter(s -> s.equals("myhost")).count();
        assertEquals(1, myhostCount, 
            "Certificate SANs should contain 'myhost' exactly once (deduplicated)");
    }

    /**
     * Test that metadata file is created with correct hostnames
     */
    @Test
    void testMetadataIncludesCustomHostnames() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.base-url", "https://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile), "Metadata file should exist");
        
        String json = Files.readString(metadataFile);
        assertTrue(json.contains("floci"), 
            "Metadata should include 'floci' hostname");
        assertTrue(json.contains("myhost"), 
            "Metadata should include 'myhost' hostname");
        assertTrue(json.contains("localhost"), 
            "Metadata should include default 'localhost' hostname");
    }

    /**
     * Test that the AWS endpoint wildcards are included when spoof-aws-endpoints is enabled
     */
    @Test
    void testCertificateIncludesAwsWildcardsWhenSpoofEnabled() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");

        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("*.amazonaws.com"),
            "Certificate SANs should include '*.amazonaws.com' when spoofing is enabled");
        assertTrue(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should include the default region wildcard '*.us-east-1.amazonaws.com'");
        assertTrue(sans.contains("localhost"),
            "Certificate SANs should still include default 'localhost'");
    }

    /**
     * A TLS wildcard matches exactly one label (RFC 6125 6.4.3), so the broad
     * *.amazonaws.com wildcards do not cover virtual-hosted S3 addressing, where the
     * bucket contributes an extra label. The DNS spoof does route those hostnames to
     * Floci, so without dedicated SANs the request dies at the handshake.
     */
    @Test
    void testCertificateCoversVirtualHostedS3WhenSpoofEnabled() throws Exception {
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("*.s3.amazonaws.com"),
            "Certificate SANs should cover global virtual-hosted S3");
        assertTrue(sans.contains("*.s3.us-east-1.amazonaws.com"),
            "Certificate SANs should cover regional virtual-hosted S3");
    }

    /**
     * Test that the regional AWS wildcard follows the configured default region
     */
    @Test
    void testAwsRegionalWildcardFollowsConfiguredDefaultRegion() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");
        System.setProperty("floci.default-region", "eu-west-1");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertTrue(sans.contains("*.eu-west-1.amazonaws.com"),
            "Certificate SANs should include '*.eu-west-1.amazonaws.com' for the configured region");
        assertFalse(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should not include the wildcard of a region that is not configured");
    }

    /**
     * Test that no AWS wildcards are included when spoof-aws-endpoints is disabled
     */
    @Test
    void testCertificateExcludesAwsWildcardsWhenSpoofDisabled() throws Exception {
        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertFalse(sans.contains("*.amazonaws.com"),
            "Certificate SANs should not include '*.amazonaws.com' when spoofing is disabled");
        assertFalse(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should not include '*.us-east-1.amazonaws.com' when spoofing is disabled");
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts Subject Alternative Names (SANs) from a certificate file.
     * 
     * @param certFile Path to the certificate file
     * @return List of SANs (DNS names and IP addresses)
     */
    private List<String> extractSansFromCertificate(Path certFile) throws Exception {
        String certPem = Files.readString(certFile);
        
        // Parse certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
            new ByteArrayInputStream(certPem.getBytes())
        );
        
        // Extract SANs
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return List.of();
        }
        
        return sans.stream()
            .filter(san -> san.size() >= 2)
            .map(san -> san.get(1).toString())
            .toList();
    }
}
