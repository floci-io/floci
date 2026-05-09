package io.github.hectorvent.floci.config;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Task 3.6: Certificate reuse logic with metadata checking
 * 
 * This test verifies that TlsConfigSource correctly:
 * 1. Checks metadata before reusing existing certificates
 * 2. Regenerates certificates when configuration changes
 * 3. Regenerates certificates when metadata is missing
 * 4. Reuses certificates when configuration is unchanged
 */
class TlsConfigSourceCertificateReuseTest {

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
        System.clearProperty("floci.storage.persistent-path");
    }

    /**
     * Test that certificate is regenerated when hostname configuration changes
     */
    @Test
    void testCertificateRegeneratedWhenHostnameChanges() throws Exception {
        // Arrange: Generate initial certificate with FLOCI_HOSTNAME=host1
        System.setProperty("floci.hostname", "host1");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act: Create TlsConfigSource - this generates the initial certificate
        new TlsConfigSource();

        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");

        // Verify initial certificate and metadata exist
        assertTrue(Files.exists(certFile), "Initial certificate should be generated");
        assertTrue(Files.exists(metadataFile), "Initial metadata should be generated");

        // Read initial metadata
        String initialMetadata = Files.readString(metadataFile);
        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();

        // Wait a bit to ensure timestamp difference
        Thread.sleep(100);

        // Change hostname configuration
        System.setProperty("floci.hostname", "host2");

        // Act: Create new TlsConfigSource - should regenerate certificate
        new TlsConfigSource();

        // Assert: Certificate and metadata should be regenerated
        assertTrue(Files.exists(certFile), "Certificate should still exist");
        assertTrue(Files.exists(metadataFile), "Metadata should still exist");

        String newMetadata = Files.readString(metadataFile);
        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();

        assertNotEquals(initialMetadata, newMetadata, 
            "Metadata should be updated with new hostname");
        assertTrue(newModifiedTime > initialModifiedTime, 
            "Certificate should be regenerated (newer timestamp)");
        assertTrue(newMetadata.contains("host2"), 
            "New metadata should contain 'host2'");
    }

    /**
     * Test that certificate is regenerated when metadata is missing
     */
    @Test
    void testCertificateRegeneratedWhenMetadataMissing() throws Exception {
        // Arrange: Generate initial certificate
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act: Create TlsConfigSource - this generates the initial certificate
        new TlsConfigSource();

        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");

        // Verify initial certificate and metadata exist
        assertTrue(Files.exists(certFile), "Initial certificate should be generated");
        assertTrue(Files.exists(metadataFile), "Initial metadata should be generated");

        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();

        // Delete metadata file to simulate old version
        Files.delete(metadataFile);
        assertFalse(Files.exists(metadataFile), "Metadata should be deleted");

        // Wait a bit to ensure timestamp difference
        Thread.sleep(100);

        // Act: Create new TlsConfigSource - should regenerate certificate
        new TlsConfigSource();

        // Assert: Certificate and metadata should be regenerated
        assertTrue(Files.exists(certFile), "Certificate should still exist");
        assertTrue(Files.exists(metadataFile), "Metadata should be regenerated");

        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        assertTrue(newModifiedTime > initialModifiedTime, 
            "Certificate should be regenerated when metadata is missing");
    }

    /**
     * Test that certificate is reused when configuration is unchanged
     */
    @Test
    void testCertificateReusedWhenConfigurationUnchanged() throws Exception {
        // Arrange: Generate initial certificate
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act: Create TlsConfigSource - this generates the initial certificate
        new TlsConfigSource();

        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");

        // Verify initial certificate and metadata exist
        assertTrue(Files.exists(certFile), "Initial certificate should be generated");
        assertTrue(Files.exists(metadataFile), "Initial metadata should be generated");

        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String initialMetadata = Files.readString(metadataFile);

        // Wait a bit to ensure timestamp difference would be visible if regenerated
        Thread.sleep(100);

        // Act: Create new TlsConfigSource with same configuration - should reuse certificate
        new TlsConfigSource();

        // Assert: Certificate and metadata should NOT be regenerated
        assertTrue(Files.exists(certFile), "Certificate should still exist");
        assertTrue(Files.exists(metadataFile), "Metadata should still exist");

        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();
        String newMetadata = Files.readString(metadataFile);

        assertEquals(initialModifiedTime, newModifiedTime, 
            "Certificate should be reused (same timestamp)");
        assertEquals(initialMetadata, newMetadata, 
            "Metadata should be unchanged");
    }

    /**
     * Test that certificate is regenerated when base URL hostname changes
     */
    @Test
    void testCertificateRegeneratedWhenBaseUrlChanges() throws Exception {
        // Arrange: Generate initial certificate with FLOCI_BASE_URL=https://host1:4566
        System.setProperty("floci.base-url", "https://host1:4566");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        // Act: Create TlsConfigSource - this generates the initial certificate
        new TlsConfigSource();

        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");

        // Verify initial certificate and metadata exist
        assertTrue(Files.exists(certFile), "Initial certificate should be generated");
        assertTrue(Files.exists(metadataFile), "Initial metadata should be generated");

        String initialMetadata = Files.readString(metadataFile);
        long initialModifiedTime = Files.getLastModifiedTime(certFile).toMillis();

        // Wait a bit to ensure timestamp difference
        Thread.sleep(100);

        // Change base URL hostname
        System.setProperty("floci.base-url", "https://host2:4566");

        // Act: Create new TlsConfigSource - should regenerate certificate
        new TlsConfigSource();

        // Assert: Certificate and metadata should be regenerated
        assertTrue(Files.exists(certFile), "Certificate should still exist");
        assertTrue(Files.exists(metadataFile), "Metadata should still exist");

        String newMetadata = Files.readString(metadataFile);
        long newModifiedTime = Files.getLastModifiedTime(certFile).toMillis();

        assertNotEquals(initialMetadata, newMetadata, 
            "Metadata should be updated with new hostname");
        assertTrue(newModifiedTime > initialModifiedTime, 
            "Certificate should be regenerated (newer timestamp)");
        assertTrue(newMetadata.contains("host2"), 
            "New metadata should contain 'host2'");
    }
}
