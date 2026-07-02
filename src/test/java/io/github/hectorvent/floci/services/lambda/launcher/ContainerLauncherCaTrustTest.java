package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContainerLauncher}'s Floci CA-trust injection helpers. These are pure
 * (no Docker / Quarkus): they cover where the CA cert is resolved from and which environment
 * variables get injected so a Lambda container trusts Floci's self-signed HTTPS endpoint.
 */
class ContainerLauncherCaTrustTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

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
        Path userCert = writeCert(tempDir.resolve("user.crt"),
                new CertificateGenerator().generateSelfSignedCertificate(
                        "floci-user", List.of(), KeyAlgorithm.RSA_2048).certificatePem());

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
    void resolveCaCertPath_acceptsButWarnsForUserLeafCert() throws Exception {
        // A leaf/server cert (issuer != subject, not a CA) is still accepted so trust injection
        // proceeds; the warning is logged. Here we assert it is accepted and is NOT a self-signed CA.
        Path leaf = writeCert(tempDir.resolve("leaf.crt"),
                new CertificateGenerator().generateCertificate(
                        "floci-leaf", List.of(), KeyAlgorithm.RSA_2048).certificatePem());

        Optional<Path> resolved = ContainerLauncher.resolveFlociCaCertPath(
                true, Optional.of(leaf.toString()), tempDir.toString());

        assertEquals(leaf, resolved.orElseThrow());
        assertFalse(ContainerLauncher.isSelfSignedCaCertificate(leaf));
    }

    @Test
    void caEnv_emptyWhenNoCert() {
        assertTrue(ContainerLauncher.flociCaEnv(Optional.empty()).isEmpty());
    }

    @Test
    void caEnv_onlySetsAppendingAndAwsScopedVars() {
        List<String> env = ContainerLauncher.flociCaEnv(Optional.of(Path.of("/host/floci-selfsigned.crt")));

        // Only NODE_EXTRA_CA_CERTS (appends to Node's CAs) and AWS_CA_BUNDLE (AWS-SDK-scoped, redirected
        // to Floci) are injected — never SSL_CERT_FILE / REQUESTS_CA_BUNDLE, which would replace the
        // whole trust store and break the Lambda's external HTTPS.
        assertEquals(List.of(
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca.crt",
                "AWS_CA_BUNDLE=/etc/floci-ca.crt"), env);
        assertFalse(env.stream().anyMatch(e -> e.startsWith("SSL_CERT_FILE=")),
                "SSL_CERT_FILE replaces the OpenSSL trust store and must not be set");
        assertFalse(env.stream().anyMatch(e -> e.startsWith("REQUESTS_CA_BUNDLE=")),
                "REQUESTS_CA_BUNDLE replaces the requests trust store and must not be set");
    }

    @Test
    void isSelfSignedCaCertificate_trueForSelfSignedCa() throws Exception {
        Path ca = writeCert(tempDir.resolve("ca.crt"),
                new CertificateGenerator().generateSelfSignedCertificate(
                        "floci-ca", List.of(), KeyAlgorithm.RSA_2048).certificatePem());

        assertTrue(ContainerLauncher.isSelfSignedCaCertificate(ca));
    }

    @Test
    void isSelfSignedCaCertificate_falseForLeafCert() throws Exception {
        Path leaf = writeCert(tempDir.resolve("leaf2.crt"),
                new CertificateGenerator().generateCertificate(
                        "floci-leaf2", List.of(), KeyAlgorithm.RSA_2048).certificatePem());

        assertFalse(ContainerLauncher.isSelfSignedCaCertificate(leaf));
    }

    @Test
    void isSelfSignedCaCertificate_falseForNonCertContent() throws Exception {
        Path garbage = Files.writeString(tempDir.resolve("garbage.crt"), "not a certificate");

        assertFalse(ContainerLauncher.isSelfSignedCaCertificate(garbage));
    }

    private static Path writeCert(Path path, String pem) throws Exception {
        return Files.writeString(path, pem);
    }
}
