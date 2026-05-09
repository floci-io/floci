package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for metadata loading and comparison in TlsConfigSource.
 * 
 * Tests the hostnameConfigChanged() method to verify:
 * - Returns true when metadata file doesn't exist
 * - Returns true when hostnames have changed
 * - Returns false when hostnames are the same (order-independent)
 * - Handles read/parse failures gracefully (returns true)
 * - Proper logging for different scenarios
 */
class TlsConfigSourceMetadataComparisonTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Set up minimal TLS configuration for testing
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        // Clean up system properties
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
        System.clearProperty("floci.hostname");
        System.clearProperty("floci.base-url");
    }

    @Test
    void testMetadataFileMissing_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        List<String> currentHostnames = List.of("localhost", "127.0.0.1");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when metadata file doesn't exist");
    }

    @Test
    void testHostnamesChanged_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with old hostnames
        List<String> oldHostnames = List.of("localhost", "127.0.0.1", "oldhost");
        createMetadataFile(tlsDir, oldHostnames);
        
        // Current hostnames are different
        List<String> currentHostnames = List.of("localhost", "127.0.0.1", "newhost");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when hostnames have changed");
    }

    @Test
    void testHostnamesUnchanged_ReturnsFalse() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with hostnames
        List<String> hostnames = List.of("localhost", "127.0.0.1", "myhost");
        createMetadataFile(tlsDir, hostnames);
        
        // Current hostnames are the same
        List<String> currentHostnames = List.of("localhost", "127.0.0.1", "myhost");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertFalse(changed, "Should return false when hostnames are unchanged");
    }

    @Test
    void testHostnamesUnchanged_DifferentOrder_ReturnsFalse() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with hostnames in one order
        List<String> hostnames = List.of("localhost", "myhost", "127.0.0.1");
        createMetadataFile(tlsDir, hostnames);
        
        // Current hostnames in different order
        List<String> currentHostnames = List.of("127.0.0.1", "localhost", "myhost");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertFalse(changed, "Should return false when hostnames are the same but in different order");
    }

    @Test
    void testHostnameAdded_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with fewer hostnames
        List<String> oldHostnames = List.of("localhost", "127.0.0.1");
        createMetadataFile(tlsDir, oldHostnames);
        
        // Current hostnames include an additional hostname
        List<String> currentHostnames = List.of("localhost", "127.0.0.1", "newhost");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when a hostname is added");
    }

    @Test
    void testHostnameRemoved_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with more hostnames
        List<String> oldHostnames = List.of("localhost", "127.0.0.1", "oldhost");
        createMetadataFile(tlsDir, oldHostnames);
        
        // Current hostnames have one removed
        List<String> currentHostnames = List.of("localhost", "127.0.0.1");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when a hostname is removed");
    }

    @Test
    void testMalformedMetadataFile_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create malformed metadata file
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");
        Files.writeString(metadataFile, "{ invalid json }");
        
        List<String> currentHostnames = List.of("localhost", "127.0.0.1");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when metadata file is malformed");
    }

    @Test
    void testMetadataWithNullHostnames_ReturnsTrue() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with null hostnames field
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");
        String json = "{\"generatedAt\":\"" + Instant.now().toString() + "\",\"flociVersion\":\"dev\"}";
        Files.writeString(metadataFile, json);
        
        List<String> currentHostnames = List.of("localhost", "127.0.0.1");

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertTrue(changed, "Should return true when metadata has no hostnames field");
    }

    @Test
    void testEmptyHostnamesList_Unchanged_ReturnsFalse() throws Exception {
        // Arrange
        Path tlsDir = tempDir.resolve("tls");
        Files.createDirectories(tlsDir);
        
        // Create metadata with empty hostnames list
        List<String> emptyHostnames = List.of();
        createMetadataFile(tlsDir, emptyHostnames);
        
        // Current hostnames are also empty
        List<String> currentHostnames = List.of();

        // Act
        boolean changed = invokeHostnameConfigChanged(tlsDir, currentHostnames);

        // Assert
        assertFalse(changed, "Should return false when both hostname lists are empty");
    }

    /**
     * Helper method to create a metadata file with specified hostnames.
     */
    private void createMetadataFile(Path tlsDir, List<String> hostnames) throws IOException {
        Path metadataFile = tlsDir.resolve("floci-selfsigned.metadata.json");
        CertificateMetadata metadata = CertificateMetadata.create(hostnames, "dev");
        
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Files.writeString(metadataFile, json);
    }

    /**
     * Helper method to invoke the private hostnameConfigChanged() method via reflection.
     * We need to disable TLS temporarily to avoid certificate generation in the constructor.
     */
    @SuppressWarnings("unchecked")
    private boolean invokeHostnameConfigChanged(Path tlsDir, List<String> currentHostnames) throws Exception {
        // Temporarily disable TLS to avoid certificate generation in constructor
        String originalTlsEnabled = System.getProperty("floci.tls.enabled");
        System.setProperty("floci.tls.enabled", "false");
        
        try {
            TlsConfigSource configSource = new TlsConfigSource();
            Method method = TlsConfigSource.class.getDeclaredMethod("hostnameConfigChanged", Path.class, List.class);
            method.setAccessible(true);
            return (boolean) method.invoke(configSource, tlsDir, currentHostnames);
        } finally {
            // Restore original TLS setting
            if (originalTlsEnabled != null) {
                System.setProperty("floci.tls.enabled", originalTlsEnabled);
            } else {
                System.clearProperty("floci.tls.enabled");
            }
        }
    }
}
