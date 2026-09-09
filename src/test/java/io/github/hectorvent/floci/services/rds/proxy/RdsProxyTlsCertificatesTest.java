package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsProxyTlsCertificatesTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureHostIsIdempotentWhenHostAlreadyCovered() {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");
        SSLContext firstContext = certs.sslContext();

        certs.ensureHost("172.17.0.5");

        assertSame(firstContext, certs.sslContext(), "no regeneration for an already-covered host");
    }

    @Test
    void ensureHostRegeneratesWhenHostIsNew() {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");
        SSLContext firstContext = certs.sslContext();

        certs.ensureHost("host.docker.internal");

        assertNotSame(firstContext, certs.sslContext(), "a genuinely new host must trigger regeneration");
    }

    @Test
    void persistsAndReloadsSansAcrossInstances() throws Exception {
        RdsProxyTlsCertificates first = newCertificates();
        first.ensureHost("172.17.0.5");
        first.ensureHost("host.docker.internal");

        Path certFile = tempDir.resolve("tls").resolve("rds-ca.crt");
        Path keyFile = tempDir.resolve("tls").resolve("rds-ca.key");
        Path metadataFile = tempDir.resolve("tls").resolve("rds-ca.metadata.json");
        assertTrue(Files.exists(certFile));
        assertTrue(Files.exists(keyFile));
        assertTrue(Files.exists(metadataFile));
        String persistedCertPem = Files.readString(certFile);

        // A fresh instance (simulating a restart) backed by the same persistent path must reuse
        // the same cert rather than regenerating — otherwise a user's trusted sslrootcert goes stale
        // on every restart even when no new host has appeared.
        RdsProxyTlsCertificates reloaded = newCertificates();
        reloaded.ensureHost("172.17.0.5");
        reloaded.ensureHost("host.docker.internal");

        assertEquals(persistedCertPem, Files.readString(certFile));
    }

    @Test
    void growingSansReusesTheSameKeyPairSoOlderCertCopiesStillValidateNewOnes() throws Exception {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        CertificateGenerator generator = new CertificateGenerator();
        var firstCertificate = generator.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        certs.ensureHost("host.docker.internal");

        var secondCertificate = generator.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        assertEquals(firstCertificate.getPublicKey(), secondCertificate.getPublicKey(),
                "growing the SAN list must reuse the existing key pair, not mint a new one, "
                        + "or a client that already trusts a prior copy of the CA loses trust");
        assertDoesNotThrow(() -> secondCertificate.verify(firstCertificate.getPublicKey()),
                "the old cert's public key must still validate the reissued cert's signature");
    }

    @Test
    void privateKeyAndTlsDirAreRestrictedToOwnerOnly() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX permissions are not supported on this filesystem");

        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        Path keyFile = tlsDir.resolve("rds-ca.key");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(keyFile),
                "the CA private key must not be readable by anyone but the owner");
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tlsDir),
                "the tls directory must not be traversable by anyone but the owner");
    }

    @Test
    void generatedCertificateIsAGenuineTrustAnchor() throws Exception {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        CertificateGenerator generator = new CertificateGenerator();
        var certificate = generator.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        assertEquals(certificate.getIssuerX500Principal(), certificate.getSubjectX500Principal());
    }

    private RdsProxyTlsCertificates newCertificates() {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }
}
