package io.github.hectorvent.floci.services.lambda.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContainerLauncher}'s Floci CA-trust injection helpers. These are pure
 * (no Docker / Quarkus): they cover where the CA cert is resolved from and which environment
 * variables get injected so a Lambda container trusts Floci's self-signed HTTPS endpoint.
 */
class ContainerLauncherCaTrustTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveCaCertPath_emptyWhenTlsDisabled() {
        assertTrue(ContainerLauncher.resolveFlociCaCertPath(false, Optional.empty(), tempDir.toString())
                .isEmpty());
    }

    @Test
    void resolveCaCertPath_selfSignedUnderPersistentPath() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path cert = Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "PEM");

        Optional<Path> resolved =
                ContainerLauncher.resolveFlociCaCertPath(true, Optional.empty(), tempDir.toString());

        assertEquals(cert, resolved.orElseThrow());
    }

    @Test
    void resolveCaCertPath_userProvidedCertWins() throws Exception {
        Path userCert = Files.writeString(tempDir.resolve("user.crt"), "PEM");

        Optional<Path> resolved = ContainerLauncher.resolveFlociCaCertPath(
                true, Optional.of(userCert.toString()), tempDir.toString());

        assertEquals(userCert, resolved.orElseThrow());
    }

    @Test
    void resolveCaCertPath_blankUserCertFallsBackToSelfSigned() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path cert = Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "PEM");

        Optional<Path> resolved =
                ContainerLauncher.resolveFlociCaCertPath(true, Optional.of("   "), tempDir.toString());

        assertEquals(cert, resolved.orElseThrow());
    }

    @Test
    void resolveCaCertPath_emptyWhenCertMissing() {
        assertTrue(ContainerLauncher.resolveFlociCaCertPath(true, Optional.empty(), tempDir.toString())
                .isEmpty());
    }

    @Test
    void caEnv_emptyWhenNoCert() {
        assertTrue(ContainerLauncher.flociCaEnv(Optional.empty()).isEmpty());
    }

    @Test
    void caEnv_setsAllTrustVarsToContainerPath() {
        List<String> env = ContainerLauncher.flociCaEnv(Optional.of(Path.of("/host/floci-selfsigned.crt")));

        assertEquals(List.of(
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca.crt",
                "AWS_CA_BUNDLE=/etc/floci-ca.crt",
                "SSL_CERT_FILE=/etc/floci-ca.crt",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca.crt"), env);
    }
}
