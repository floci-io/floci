package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsProxyTlsCertificatesTest {

    @TempDir
    Path tempDir;

    @Test
    void usesTheExistingSharedCaWithoutCreatingAnotherRdsAuthority() throws Exception {
        FlociCertificateAuthority sharedCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        newCertificates().sslContext();

        assertEquals(sharedCa.caPem(), Files.readString(sharedCa.certificatePath()));
        assertEquals(sharedCa.caPem(), Files.readString(tempDir.resolve("tls/rds-ca.crt")));
        assertFalse(Files.exists(tempDir.resolve("tls/rds-ca.key")));
    }

    @Test
    void legacyPathsCannotOverwriteOrDeleteTheSharedPrivateKeyThroughSymlinks() throws Exception {
        FlociCertificateAuthority sharedCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        Path tlsDir = sharedCa.certificatePath().getParent();
        Path sharedKeyFile = tlsDir.resolve(FlociCertificateAuthority.CA_KEY_NAME);
        String sharedKey = Files.readString(sharedKeyFile);
        Path legacyCertificate = Files.createSymbolicLink(tlsDir.resolve("rds-ca.crt"), sharedKeyFile);
        Path legacyKey = Files.createSymbolicLink(tlsDir.resolve("rds-ca.key"), sharedKeyFile);

        newCertificates().sslContext();

        assertFalse(Files.isSymbolicLink(legacyCertificate));
        assertEquals(sharedCa.caPem(), Files.readString(legacyCertificate));
        assertFalse(Files.isSymbolicLink(legacyKey));
        assertEquals(sharedKey, Files.readString(sharedKeyFile));
        assertEquals(sharedCa.caPem(), Files.readString(sharedCa.certificatePath()));
    }

    @Test
    void aFailedLegacyCertificateWriteDoesNotPublishAnInitializedContext() throws Exception {
        FlociCertificateAuthority sharedCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        Path legacyCertificate = Files.createDirectory(tempDir.resolve("tls/rds-ca.crt"));
        Path obstruction = Files.writeString(legacyCertificate.resolve("keep"), "not a certificate");
        RdsProxyTlsCertificates certs = newCertificates();

        assertThrows(IllegalStateException.class, certs::sslContext);
        Files.delete(obstruction);
        Files.delete(legacyCertificate);
        assertDoesNotThrow(certs::sslContext);

        assertEquals(sharedCa.caPem(), Files.readString(legacyCertificate));
        try (Stream<Path> files = Files.list(legacyCertificate.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

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

        Path certFile = tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_CERT_NAME);
        Path keyFile = tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_KEY_NAME);
        Path metadataFile = tempDir.resolve("tls").resolve("rds-ca.metadata.json");
        assertTrue(Files.exists(certFile));
        assertTrue(Files.exists(keyFile));
        assertTrue(Files.exists(metadataFile));
        String persistedCertPem = Files.readString(certFile);

        RdsProxyTlsCertificates reloaded = newCertificates();
        SSLContext reloadedContext = reloaded.sslContext();
        reloaded.ensureHost("172.17.0.5");
        reloaded.ensureHost("host.docker.internal");

        assertSame(reloadedContext, reloaded.sslContext(), "persisted hosts do not need registration again");
        assertEquals(persistedCertPem, Files.readString(certFile));
    }

    @Test
    void growingSansReusesTheSameKeyPairSoOlderCertCopiesStillValidateNewOnes() throws Exception {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        X509Certificate firstCertificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_CERT_NAME)));

        certs.ensureHost("host.docker.internal");

        X509Certificate secondCertificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_CERT_NAME)));

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

        Path keyFile = tlsDir.resolve(FlociCertificateAuthority.CA_KEY_NAME);
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

        X509Certificate certificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_CERT_NAME)));

        assertEquals(certificate.getIssuerX500Principal(), certificate.getSubjectX500Principal());
        assertTrue(certificate.getBasicConstraints() >= 0);
    }

    @Test
    void replacesAnExpiredLegacyCertificateAndRetainsItsSansWithoutChangingTheSharedCa() throws Exception {
        FlociCertificateAuthority sharedCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        Path tlsDir = sharedCa.certificatePath().getParent();
        CertificateGenerator.GeneratedCertificate legacy = new CertificateGenerator()
                .generateCaCertificate("Legacy RDS CA", Instant.now().minusSeconds(60));
        assertThrows(CertificateExpiredException.class, () -> Pem.parseCertificate(legacy.certificatePem()).checkValidity());
        Path certFile = tlsDir.resolve("rds-ca.crt");
        Path keyFile = tlsDir.resolve("rds-ca.key");
        Files.writeString(certFile, legacy.certificatePem());
        Files.writeString(keyFile, legacy.privateKeyPem());
        Files.writeString(tlsDir.resolve("rds-ca.metadata.json"), "{\"hostnames\":[\"localhost\",\"172.17.0.5\"]}");

        RdsProxyTlsCertificates migrated = newCertificates();
        SSLContext migratedContext = migrated.sslContext();
        migrated.ensureHost("172.17.0.5");
        assertSame(migratedContext, migrated.sslContext(), "legacy SAN metadata is retained");
        migrated.ensureHost("host.docker.internal");
        newCertificates().ensureHost("host.docker.internal");

        assertEquals(sharedCa.caPem(), Files.readString(sharedCa.certificatePath()));
        assertEquals(sharedCa.caPem(), Files.readString(certFile));
        assertFalse(Files.exists(keyFile));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{", "null", "{\"hostnames\":[null]}", "{\"hostnames\":[\" \"]}", "{\"hostnames\":[]}"})
    void recoveringMetadataDoesNotReplaceAValidTrustAnchor(String metadata) throws Exception {
        newCertificates().sslContext();
        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve(FlociCertificateAuthority.CA_CERT_NAME);
        String trustedCertificate = Files.readString(certFile);
        if (metadata != null) {
            Files.writeString(tlsDir.resolve("rds-ca.metadata.json"), metadata);
        } else {
            Files.delete(tlsDir.resolve("rds-ca.metadata.json"));
        }

        newCertificates().sslContext();

        assertEquals(trustedCertificate, Files.readString(certFile));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removesAnUnusableLegacyCaKeyWithoutReplacingTheSharedTrustAnchor(boolean malformedKey) throws Exception {
        FlociCertificateAuthority sharedCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        CertificateGenerator.GeneratedCertificate legacy = new CertificateGenerator().generateCaCertificate("Legacy RDS CA");
        Path certFile = tempDir.resolve("tls/rds-ca.crt");
        Path keyFile = tempDir.resolve("tls/rds-ca.key");
        Files.writeString(certFile, legacy.certificatePem());
        String replacementKey = malformedKey ? "not a private key"
                : new CertificateGenerator().generateCaCertificate("Unrelated CA").privateKeyPem();
        Files.writeString(keyFile, replacementKey);

        newCertificates().sslContext();

        assertEquals(sharedCa.caPem(), Files.readString(sharedCa.certificatePath()));
        assertEquals(sharedCa.caPem(), Files.readString(certFile));
        assertFalse(Files.exists(keyFile));
    }

    @Test
    void renewsAnExpiringLeafWithoutChangingTheCaOrRegisteringANewHost() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        RdsProxyTlsCertificates certs = newCertificates(clock);
        certs.ensureHost("host.docker.internal");
        SSLContext previous = certs.sslContext();
        Path certFile = tempDir.resolve("tls").resolve(FlociCertificateAuthority.CA_CERT_NAME);
        String trustedCa = Files.readString(certFile);

        when(clock.instant()).thenReturn(Instant.now().plus(365, ChronoUnit.DAYS).minusSeconds(30));
        SSLContext renewed = certs.sslContext();

        assertNotSame(previous, renewed);
        assertSame(renewed, certs.sslContext());
        certs.ensureHost("host.docker.internal");
        assertSame(renewed, certs.sslContext(), "renewal retains the previous SAN set");
        assertEquals(trustedCa, Files.readString(certFile));
        RdsProxyTlsCertificates reloaded = newCertificates(clock);
        SSLContext reloadedContext = reloaded.sslContext();
        reloaded.ensureHost("host.docker.internal");
        assertSame(reloadedContext, reloaded.sslContext(), "renewal persists the previous SAN set");
    }

    @Test
    void aLeafCappedAtCaExpiryDoesNotRenewContinuouslyOrRotateTheCa() throws Exception {
        CertificateGenerator.GeneratedCertificate ca = new CertificateGenerator().generateCaCertificate(
                "Short-lived CA", Instant.now().plusSeconds(60));
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path certFile = tlsDir.resolve(FlociCertificateAuthority.CA_CERT_NAME);
        Files.writeString(certFile, ca.certificatePem());
        Files.writeString(tlsDir.resolve(FlociCertificateAuthority.CA_KEY_NAME), ca.privateKeyPem());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.now());
        RdsProxyTlsCertificates certs = newCertificates(clock);
        SSLContext context = certs.sslContext();

        when(clock.instant()).thenReturn(ca.notAfter().minusSeconds(1));
        assertSame(context, certs.sslContext());
        when(clock.instant()).thenReturn(ca.notAfter().plusSeconds(1));
        assertThrows(IllegalStateException.class, certs::sslContext);
        assertEquals(ca.certificatePem(), Files.readString(certFile));
    }

    private RdsProxyTlsCertificates newCertificates() {
        return newCertificates(Clock.systemUTC());
    }

    private RdsProxyTlsCertificates newCertificates(Clock clock) {
        return new RdsProxyTlsCertificates(FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls")),
                new CertificateGenerator(), clock);
    }
}
