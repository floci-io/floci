package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CA bundle handed to every container Floci launches: the JVM's public roots followed by
 * Floci's trust anchor, and the environment entries that make each runtime read it.
 */
class ContainerCaBundleTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void forgetBootstrapTlsDir() {
        // TlsConfigSource.resolvedTlsDir is static: a TLS-on bootstrap run by another test class
        // in this JVM would otherwise steer hostPath() at that test's directory.
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        System.clearProperty("floci.tls.enabled");
    }

    @Test
    void writeProducesTheSystemRootsFollowedByTheAnchor() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);

        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());

        assertEquals(tlsDir.resolve("floci-ca-bundle.pem"), bundle);
        List<X509Certificate> certificates = parseAll(Files.readAllBytes(bundle));
        assertTrue(certificates.size() > 50, "expected the JVM's public roots, got " + certificates.size());
        assertEquals(ca.certificate(), certificates.get(certificates.size() - 1), "the Floci CA closes the bundle");
        assertEquals(1, certificates.stream().filter(ca.certificate()::equals).count());
    }

    @Test
    void writeIsIdempotent() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);

        byte[] first = Files.readAllBytes(ContainerCaBundle.write(tlsDir, ca.certificatePath()));
        byte[] second = Files.readAllBytes(ContainerCaBundle.write(tlsDir, ca.certificatePath()));

        assertTrue(java.util.Arrays.equals(first, second));
        try (var files = Files.list(tlsDir)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "no temp file left behind");
        }
    }

    @Test
    void writeKeepsEveryCertificateOfAChainFileAndDropsPrivateKeys() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate("floci", List.of("floci"),
                KeyAlgorithm.RSA_2048, null);
        Path chainFile = Files.writeString(tempDir.resolve("user-chain.pem"),
                leaf.certificatePem() + leaf.privateKeyPem() + ca.caPem());

        String bundle = Files.readString(ContainerCaBundle.write(tlsDir, chainFile));

        assertFalse(bundle.contains("PRIVATE KEY"), "a private key must never reach a container");
        List<X509Certificate> certificates = parseAll(bundle.getBytes());
        int size = certificates.size();
        assertEquals(ca.certificate(), certificates.get(size - 1));
        assertEquals(new CertificateGenerator().parseCertificate(leaf.certificatePem()), certificates.get(size - 2));
    }

    @Test
    void writeRefusesAnAnchorFileWithoutACertificate() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path notACertificate = Files.writeString(tempDir.resolve("empty.pem"), "nothing to trust here\n");

        assertThrows(IllegalArgumentException.class, () -> ContainerCaBundle.write(tlsDir, notACertificate));
        assertFalse(Files.exists(tlsDir.resolve("floci-ca-bundle.pem")));
    }

    @Test
    void hostPathIsEmptyWhenTlsIsOff() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(false);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.empty(), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathIsEmptyWhenTheBootWriteDidNotHappen() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.empty(), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFindsTheBundleUnderTheConfiguredPersistentPath() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.of(bundle), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFollowsTheDirectoryTheBootstrapUsed() throws Exception {
        // application.yml alone may name a persistent path the bootstrap never saw; the bundle
        // lives where TlsConfigSource wrote it, next to the CA.
        Path bootDir = tempDir.resolve("boot");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", bootDir.toString());
        try {
            new TlsConfigSource();
        } finally {
            System.clearProperty("floci.tls.enabled");
            System.clearProperty("floci.tls.self-signed");
            System.clearProperty("floci.storage.persistent-path");
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve("elsewhere").toString());

        assertEquals(Optional.of(bootDir.resolve("tls").resolve("floci-ca-bundle.pem")),
                ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFollowsTheDirectoryOfAUserCertificateBoot() throws Exception {
        Path bootDir = tempDir.resolve("boot");
        CertificateGenerator.GeneratedCertificate user = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(tempDir.resolve("user.crt"), user.certificatePem());
        Path userKey = Files.writeString(tempDir.resolve("user.key"), user.privateKeyPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", bootDir.toString());
        try {
            new TlsConfigSource();
        } finally {
            System.clearProperty("floci.tls.enabled");
            System.clearProperty("floci.tls.cert-path");
            System.clearProperty("floci.tls.key-path");
            System.clearProperty("floci.storage.persistent-path");
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve("elsewhere").toString());

        assertEquals(Optional.of(bootDir.resolve("tls").resolve("floci-ca-bundle.pem")),
                ContainerCaBundle.hostPath(config));
    }

    @Test
    void envPointsEveryRuntimeAtTheContainerPath() {
        assertEquals(List.of(
                "SSL_CERT_FILE=/etc/floci-ca-bundle.pem",
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem",
                "AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"), ContainerCaBundle.env());
    }

    @Test
    void appendEnvKeepsTheCallerEntriesFirstAndUnchanged() {
        List<String> merged = ContainerCaBundle.appendEnv(List.of("FOO=bar", "SSL_CERT_FILE=/my/own.pem", "AWS_CA_BUNDLE"));

        assertEquals(List.of("FOO=bar", "SSL_CERT_FILE=/my/own.pem", "AWS_CA_BUNDLE"), merged.subList(0, 3));
        assertEquals(List.of(
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem"), merged.subList(3, merged.size()));
    }

    @Test
    void appendEnvOfNothingIsTheTrustEnv() {
        assertEquals(ContainerCaBundle.env(), ContainerCaBundle.appendEnv(null));
        assertEquals(ContainerCaBundle.env(), ContainerCaBundle.appendEnv(List.of()));
    }

    private static List<X509Certificate> parseAll(byte[] pem) throws Exception {
        List<X509Certificate> certificates = new ArrayList<>();
        for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))) {
            certificates.add((X509Certificate) certificate);
        }
        return certificates;
    }
}
