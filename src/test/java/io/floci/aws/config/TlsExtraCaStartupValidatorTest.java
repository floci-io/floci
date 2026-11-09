package io.floci.aws.config;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The floci.tls.extra-ca-path file is checked at startup, from whichever source sets it, with TLS on or off.
 */
class TlsExtraCaStartupValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void aMissingExtraCaFileStopsTheStartWithTlsOff() {
        Path missing = tempDir.resolve("missing-ca.pem");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, Optional.of(missing.toString())).onStart(new StartupEvent()));

        assertTrue(refused.getMessage().contains("not found or not readable: " + missing), refused.getMessage());
    }

    @Test
    void aMissingExtraCaFileStopsTheStartWithTlsOn() {
        Path missing = tempDir.resolve("missing-ca.pem");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, Optional.of(missing.toString())).onStart(new StartupEvent()));

        assertTrue(refused.getMessage().contains(missing.toString()), refused.getMessage());
    }

    @Test
    void anExtraCaFileWithoutACertificateStopsTheStart() throws Exception {
        Path empty = Files.writeString(tempDir.resolve("empty.pem"), "nothing to trust here\n");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, Optional.of(empty.toString())).onStart(new StartupEvent()));

        assertTrue(refused.getMessage().contains("holds no certificate: " + empty), refused.getMessage());
    }

    @Test
    void anExtraCaPathNamingADirectoryStopsTheStart() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, Optional.of(tempDir.toString())).onStart(new StartupEvent()));

        assertTrue(refused.getMessage().contains(tempDir.toString()), refused.getMessage());
    }

    @Test
    void anExtraCaFileWithACertificateLetsTheStartThrough() {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("private-ca"));

        assertDoesNotThrow(() -> validator(false, Optional.of(privateCa.certificatePath().toString()))
                .onStart(new StartupEvent()));
    }

    /** A combined certificate-and-key file passes on its certificate; the key is ignored. */
    @Test
    void anExtraCaFileHoldingAPrivateKeyLetsTheStartThrough() throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("private-ca"));
        CertificateGenerator.GeneratedCertificate leaf = privateCa.issueServerCertificate("floci", List.of("floci"),
                KeyAlgorithm.RSA_2048, null);
        Path withKey = Files.writeString(tempDir.resolve("ca-and-key.pem"), privateCa.caPem() + leaf.privateKeyPem());

        assertDoesNotThrow(() -> validator(false, Optional.of(withKey.toString())).onStart(new StartupEvent()));
    }

    @Test
    void anUnsetOrBlankExtraCaPathIsNotChecked() {
        assertDoesNotThrow(() -> validator(false, Optional.empty()).onStart(new StartupEvent()));
        assertDoesNotThrow(() -> validator(false, Optional.of(" ")).onStart(new StartupEvent()));
    }

    private static TlsExtraCaStartupValidator validator(boolean tlsEnabled, Optional<String> extraCaPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(tlsEnabled);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        return new TlsExtraCaStartupValidator(config);
    }
}
