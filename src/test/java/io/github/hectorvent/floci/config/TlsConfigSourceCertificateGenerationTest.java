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
        System.clearProperty("floci.services.iot.endpoint-address");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path certFile = tempDir.resolve("tls/floci-server.crt");
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
        Path metadataFile = tempDir.resolve("tls/floci-server.metadata.json");
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
     * The IoT endpoint address is what devices verify on 8883 and 443, so the boot certificate
     * covers it even when it has more labels than the wildcard SAN matches.
     */
    @Test
    void testCertificateIncludesIotEndpointAddress() throws Exception {
        System.setProperty("floci.services.iot.endpoint-address", "iot.example.localhost.floci.io:8883");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(sans.contains("iot.example.localhost.floci.io"), sans.toString());
        assertFalse(sans.contains("iot.example.localhost.floci.io:8883"), "the port is not part of the name");
        assertTrue(Files.readString(tempDir.resolve("tls/floci-server.metadata.json")).contains("iot.example.localhost.floci.io"),
            "the metadata records it as a configured name, so a later change is detected");
    }

    @Test
    void testIotEndpointAddressAddedAfterBootRegeneratesTheCertificate() throws Exception {
        new TlsConfigSource();
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertFalse(extractSansFromCertificate(certFile).contains("iot.example.localhost.floci.io"));

        System.setProperty("floci.services.iot.endpoint-address", "iot.example.localhost.floci.io");
        new TlsConfigSource();

        assertTrue(extractSansFromCertificate(certFile).contains("iot.example.localhost.floci.io"),
            "a changed endpoint address is a hostname configuration change");
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
