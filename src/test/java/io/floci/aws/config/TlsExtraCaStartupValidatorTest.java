package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMissingExtraCaFileStopsTheStart(boolean tlsEnabled) {
        Path missing = tempDir.resolve("missing-ca.pem");

        String refusal = refusal(tlsEnabled, missing.toString());

        assertTrue(refusal.contains("not found or not readable: " + missing), refusal);
    }

    @Test
    void anExtraCaFileWithoutACertificateStopsTheStart() throws Exception {
        Path empty = Files.writeString(tempDir.resolve("empty.pem"), "nothing to trust here\n");

        String refusal = refusal(false, empty.toString());

        assertTrue(refusal.contains(empty + " holds no certificate"), refusal);
    }

    @Test
    void anExtraCaPathNamingADirectoryStopsTheStart() {
        String refusal = refusal(false, tempDir.toString());

        assertTrue(refusal.contains(tempDir.toString()), refusal);
    }

    @Test
    void anExtraCaFileWithACertificateLetsTheStartThrough() {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("private-ca"));

        assertDoesNotThrow(() -> start(Optional.of(privateCa.certificatePath().toString())));
    }

    /** A combined certificate-and-key file passes on its certificate; the key is ignored. */
    @Test
    void anExtraCaFileHoldingAPrivateKeyLetsTheStartThrough() throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("private-ca"));
        Path withKey = TlsTestSupport.caWithPrivateKey(privateCa, tempDir.resolve("ca-and-key.pem"));

        assertDoesNotThrow(() -> start(Optional.of(withKey.toString())));
    }

    @Test
    void anUnsetOrBlankExtraCaPathIsNotChecked() {
        assertDoesNotThrow(() -> start(Optional.empty()));
        assertDoesNotThrow(() -> start(Optional.of(" ")));
    }

    // The message the startup check stops Floci with, with TLS as tlsEnabled says and extraCaPath set.
    private static String refusal(boolean tlsEnabled, String extraCaPath) {
        return assertThrows(IllegalStateException.class,
                () -> validator(tlsEnabled, Optional.of(extraCaPath)).onStart(new StartupEvent())).getMessage();
    }

    // Runs the startup check with TLS off and extraCaPath as the setting.
    private static void start(Optional<String> extraCaPath) {
        validator(false, extraCaPath).onStart(new StartupEvent());
    }

    private static TlsExtraCaStartupValidator validator(boolean tlsEnabled, Optional<String> extraCaPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(tlsEnabled);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        return new TlsExtraCaStartupValidator(config);
    }
}
