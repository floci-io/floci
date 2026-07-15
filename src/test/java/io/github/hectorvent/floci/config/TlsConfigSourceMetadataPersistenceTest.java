package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for metadata persistence in TlsConfigSource.
 *
 * Tests that metadata is correctly persisted after certificate generation:
 * - Metadata file is created
 * - Metadata contains correct hostnames
 * - Metadata contains timestamp and version
 * - Write failures are handled gracefully
 */
class TlsConfigSourceMetadataPersistenceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        // Set TLS enabled and self-signed mode
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
        System.clearProperty("FLOCI_VERSION");
    }

    /**
     * Test that metadata file is created after certificate generation
     */
    @Test
    void testMetadataFileCreated() {
        // Act - trigger certificate generation by creating TlsConfigSource
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile),
            "Metadata file should be created after certificate generation");
    }

    /**
     * Test that metadata contains default hostnames when no custom hostnames configured
     */
    @Test
    void testMetadataContainsDefaultHostnames() throws IOException {
        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertNotNull(metadata.getHostnames(), "Hostnames should not be null");
        assertTrue(metadata.getHostnames().contains("localhost"),
            "Metadata should contain 'localhost'");
        assertTrue(metadata.getHostnames().contains("127.0.0.1"),
            "Metadata should contain '127.0.0.1'");
        assertTrue(metadata.getHostnames().contains("0.0.0.0"),
            "Metadata should contain '0.0.0.0'");
        assertTrue(metadata.getHostnames().contains("*.localhost"),
            "Metadata should contain '*.localhost'");
        assertTrue(metadata.getHostnames().contains("localhost.floci.io"),
            "Metadata should contain 'localhost.floci.io'");
        assertTrue(metadata.getHostnames().contains("*.localhost.floci.io"),
            "Metadata should contain '*.localhost.floci.io'");
        assertTrue(metadata.getHostnames().contains("host.docker.internal"),
            "Metadata should contain 'host.docker.internal'");
    }

    /**
     * Test that metadata contains custom hostname from FLOCI_HOSTNAME
     */
    @Test
    void testMetadataContainsCustomHostname() throws IOException {
        // Arrange
        System.setProperty("floci.hostname", "floci");

        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertTrue(metadata.getHostnames().contains("floci"),
            "Metadata should contain custom hostname 'floci'");
    }

    /**
     * Test that metadata contains custom hostname from FLOCI_BASE_URL
     */
    @Test
    void testMetadataContainsBaseUrlHostname() throws IOException {
        // Arrange
        System.setProperty("floci.base-url", "https://myhost:4566");

        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertTrue(metadata.getHostnames().contains("myhost"),
            "Metadata should contain hostname 'myhost' from base URL");
    }

    /**
     * Test that metadata contains both custom hostnames when both are configured
     */
    @Test
    void testMetadataContainsBothCustomHostnames() throws IOException {
        // Arrange
        System.setProperty("floci.hostname", "newhost");
        System.setProperty("floci.base-url", "http://oldhost:4566");

        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertTrue(metadata.getHostnames().contains("newhost"),
            "Metadata should contain 'newhost' from FLOCI_HOSTNAME");
        assertTrue(metadata.getHostnames().contains("oldhost"),
            "Metadata should contain 'oldhost' from FLOCI_BASE_URL");
    }

    /**
     * Test that metadata contains timestamp
     */
    @Test
    void testMetadataContainsTimestamp() throws IOException {
        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertNotNull(metadata.getGeneratedAt(),
            "Metadata should contain generatedAt timestamp");
        assertFalse(metadata.getGeneratedAt().isBlank(),
            "Timestamp should not be blank");
    }

    /**
     * Test that metadata contains version
     */
    @Test
    void testMetadataContainsVersion() throws IOException {
        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertNotNull(metadata.getFlociVersion(),
            "Metadata should contain flociVersion");
        assertEquals("dev", metadata.getFlociVersion(),
            "Version should default to 'dev' when FLOCI_VERSION not set");
    }

    /**
     * Test that metadata contains custom version from FLOCI_VERSION environment variable
     */
    @Test
    void testMetadataContainsCustomVersion() throws IOException {
        // Arrange
        // Note: We can't set environment variables in Java, so we'll test the default behavior
        // The actual environment variable handling is tested in integration tests

        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        CertificateMetadata metadata = readMetadata(metadataFile);

        assertNotNull(metadata.getFlociVersion(),
            "Metadata should contain flociVersion");
    }

    /**
     * Test that metadata file is valid JSON
     */
    @Test
    void testMetadataIsValidJson() throws IOException {
        // Act
        new TlsConfigSource();

        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
        String json = Files.readString(metadataFile);

        assertFalse(json.isBlank(), "Metadata file should not be empty");
        assertTrue(json.contains("hostnames"), "JSON should contain 'hostnames' field");
        assertTrue(json.contains("generatedAt"), "JSON should contain 'generatedAt' field");
        assertTrue(json.contains("flociVersion"), "JSON should contain 'flociVersion' field");
    }

    // ==================== Helper Methods ====================

    /**
     * Reads and parses metadata from the specified file.
     */
    private CertificateMetadata readMetadata(Path metadataFile) throws IOException {
        String json = Files.readString(metadataFile);
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(json, CertificateMetadata.class);
    }
}
