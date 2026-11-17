package io.floci.aws.config;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TlsConfigSource} writes the container CA bundle at boot, in both certificate modes, and
 * a bundle that cannot be written never stops the boot.
 */
class TlsConfigSourceContainerCaBundleTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.tls.cert-path");
        System.clearProperty("floci.tls.key-path");
        System.clearProperty("floci.tls.extra-ca-path");
        System.clearProperty("floci.storage.persistent-path");
    }

    /** Containers keep the bundle they had: the floci.tls.extra-ca-path CAs are for Floci's own clients only. */
    @Test
    void theBundleAndTrustStoreLeaveTheExtraCasOut() throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("private-ca"));
        Path persistent = Files.createDirectories(tempDir.resolve("data"));
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", persistent.toString());
        System.setProperty("floci.tls.extra-ca-path", privateCa.certificatePath().toString());

        new TlsConfigSource();

        String bundle = Files.readString(persistent.resolve("tls/" + ContainerCaBundle.FILE_NAME));
        assertFalse(bundle.contains(privateCa.caPem().strip()), "the extra CA stays out of the PEM bundle");
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(
                persistent.resolve("tls/" + ContainerCaBundle.JAVA_TRUST_STORE_FILE_NAME))) {
            trustStore.load(in, null);
        }
        for (String alias : Collections.list(trustStore.aliases())) {
            assertFalse(privateCa.certificate().equals(trustStore.getCertificate(alias)),
                    "the extra CA stays out of the Java trust store");
        }
    }

    @Test
    void selfSignedBootWritesTheBundleEndingWithTheLocalCa() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        Path bundle = tempDir.resolve("tls/floci-ca-bundle.pem");
        assertTrue(Files.exists(bundle), "bundle written at boot");
        String pem = Files.readString(bundle).strip();
        String caPem = Files.readString(tempDir.resolve("tls/floci-root-ca.crt")).strip();
        assertTrue(pem.endsWith(caPem), "the local CA is the last entry");
        assertTrue(pem.indexOf("-----BEGIN CERTIFICATE-----") < pem.lastIndexOf("-----BEGIN CERTIFICATE-----"),
                "public roots precede the CA");
        assertTrue(Files.isRegularFile(tempDir.resolve("tls/floci-ca-bundle.p12")), "Java trust store written at boot");
    }

    @Test
    void aJavaTrustStoreThatCannotBeWrittenLeavesTheBundleAndTheBoot() throws Exception {
        // A directory where the trust store goes, with something in it, so it cannot be replaced.
        Files.writeString(Files.createDirectories(tempDir.resolve("tls/floci-ca-bundle.p12")).resolve("x"), "x");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        assertTrue(Files.isRegularFile(tempDir.resolve("tls/floci-ca-bundle.pem")), "bundle written at boot");
        assertTrue(Files.isDirectory(tempDir.resolve("tls/floci-ca-bundle.p12")), "the boot left the obstacle alone");
    }

    @Test
    void userCertificateBootWritesTheBundleEndingWithThatCertificate() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(userDir.resolve("user.crt"), generated.certificatePem());
        Path userKey = Files.writeString(userDir.resolve("user.key"), generated.privateKeyPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        String pem = Files.readString(tempDir.resolve("tls/floci-ca-bundle.pem")).strip();
        assertTrue(pem.endsWith(generated.certificatePem().strip()));
        assertFalse(Files.exists(tempDir.resolve("tls/floci-root-ca.crt")), "custom-cert mode creates no CA");
        assertEquals(tempDir.resolve("tls"), TlsConfigSource.resolvedTlsDir(), "CDI consumers look where the boot wrote");
    }

    @Test
    void tlsOffWritesNoBundle() {
        System.setProperty("floci.tls.enabled", "false");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        assertFalse(Files.exists(tempDir.resolve("tls")));
    }

    @Test
    void aBundleThatCannotBeWrittenDoesNotStopTheBoot() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(userDir.resolve("user.crt"), generated.certificatePem());
        Path userKey = Files.writeString(userDir.resolve("user.key"), generated.privateKeyPem());
        Path persistent = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(persistent.resolve("tls"), "a file where the tls directory should be");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", persistent.toString());

        TlsConfigSource source = new TlsConfigSource();

        assertEquals(userCert.toString(), source.getValue("quarkus.tls.key-store.pem.0.cert"));
        assertTrue(Files.isRegularFile(persistent.resolve("tls")), "the boot left the obstacle alone");
    }
}
