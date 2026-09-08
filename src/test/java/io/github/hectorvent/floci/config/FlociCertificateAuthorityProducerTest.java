package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The producer must hand beans the directory the config bootstrap used, and only fall back to the
 * configured persistent path when the bootstrap laid nothing down.
 */
class FlociCertificateAuthorityProducerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearBootstrap() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
        new TlsConfigSource();
    }

    private static EmulatorConfig configWithPersistentPath(String path) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.persistentPath()).thenReturn(path);
        return config;
    }

    @Test
    void usesTheBootstrapDirectoryWhenTheBootstrapIssuedTheLeaf() {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        new TlsConfigSource();

        Path dir = FlociCertificateAuthorityProducer.tlsDir(configWithPersistentPath("/elsewhere/from/yaml"));

        assertEquals(tempDir.resolve("tls"), dir, "one directory for the served leaf and ca.pem, or two CAs would exist");
    }

    @Test
    void fallsBackToTheConfiguredPathWhenTlsIsOff() {
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();

        Path dir = FlociCertificateAuthorityProducer.tlsDir(configWithPersistentPath(tempDir.toString()));

        assertEquals(tempDir.resolve("tls"), dir);
    }
}
