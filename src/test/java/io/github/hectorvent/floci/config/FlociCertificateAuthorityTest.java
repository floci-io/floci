package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlociCertificateAuthorityTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCaFilesWithOwnerOnlyKey() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertTrue(Files.exists(tempDir.resolve("floci-root-ca.crt")));
        assertTrue(Files.exists(tempDir.resolve("floci-root-ca.key")));
        assertEquals(tempDir.resolve("floci-root-ca.crt"), ca.certificatePath());
        assertTrue(ca.certificate().getBasicConstraints() >= 0, "CA must be cA=true");
        assertTrue(ca.certificate().getKeyUsage()[5], "CA must assert keyCertSign");
        assertEquals(ca.certificate().getSubjectX500Principal(), ca.certificate().getIssuerX500Principal());
        assertEquals("CN=Floci Local CA", ca.certificate().getSubjectX500Principal().getName());
        assertTrue(ca.caPem().startsWith("-----BEGIN CERTIFICATE-----"));
        assertEquals(Files.readString(tempDir.resolve("floci-root-ca.crt")), ca.caPem(), "caPem is the file's bytes");
        assertTrue(ca.fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), ca.fingerprint());
        assertEquals(ca.fingerprint(), FlociCertificateAuthority.loadOrCreate(tempDir).fingerprint());

        Set<PosixFilePermission> keyPerms = Files.getPosixFilePermissions(tempDir.resolve("floci-root-ca.key"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), keyPerms);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tempDir));
    }

    @Test
    void createsTheDirectoryWhenMissing() {
        Path tlsDir = tempDir.resolve("nested").resolve("tls");

        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);

        assertTrue(Files.exists(tlsDir.resolve("floci-root-ca.key")));
        assertTrue(ca.isIssuedByUs(parse(ca.issueClientCertificate("device").certificatePem())));
    }

    @Test
    void reloadsTheSameCaAcrossRestarts() throws Exception {
        FlociCertificateAuthority first = FlociCertificateAuthority.loadOrCreate(tempDir);
        Files.setPosixFilePermissions(tempDir.resolve("floci-root-ca.key"), PosixFilePermissions.fromString("rw-r--r--"));

        FlociCertificateAuthority second = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertEquals(first.certificate(), second.certificate());
        assertEquals(first.key(), second.key());
        assertEquals(first.caPem(), second.caPem());
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(tempDir.resolve("floci-root-ca.key")), "permissions are re-tightened on load");
    }

    @Test
    void corruptCaIsRegeneratedNotSilentlyKept() throws Exception {
        FlociCertificateAuthority first = FlociCertificateAuthority.loadOrCreate(tempDir);
        Files.writeString(tempDir.resolve("floci-root-ca.crt"), "-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----\n");

        FlociCertificateAuthority second = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertNotEquals(first.certificate(), second.certificate());
        assertTrue(second.certificate().getBasicConstraints() >= 0);
        assertEquals(second.caPem(), Files.readString(tempDir.resolve("floci-root-ca.crt")));
    }

    @Test
    void keyThatDoesNotMatchTheCertificateIsRegenerated() throws Exception {
        FlociCertificateAuthority first = FlociCertificateAuthority.loadOrCreate(tempDir);
        var stranger = new CertificateGenerator().generateCaCertificate("Other CA");
        Files.writeString(tempDir.resolve("floci-root-ca.key"), stranger.privateKeyPem());

        FlociCertificateAuthority second = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertNotEquals(first.certificate(), second.certificate(), "a pair that cannot sign must not be kept");
        assertTrue(second.isIssuedByUs(parse(second.issueClientCertificate("device").certificatePem())));
    }

    @Test
    void expiredCaIsRegenerated() throws Exception {
        CertificateGenerator gen = new CertificateGenerator();
        java.security.KeyPair keyPair = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var dn = new org.bouncycastle.asn1.x500.X500Name("CN=" + FlociCertificateAuthority.COMMON_NAME);
        X509Certificate expired = gen.signCertificate(dn, keyPair.getPublic(), dn, keyPair.getPrivate(), List.of(),
                true, null, -1);
        Files.writeString(tempDir.resolve("floci-root-ca.crt"), gen.toPem(expired));
        Files.writeString(tempDir.resolve("floci-root-ca.key"), gen.toPem(keyPair.getPrivate()));

        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertNotEquals(expired, ca.certificate(), "an expired trust anchor is useless and must be replaced");
        ca.certificate().checkValidity();
    }

    @Test
    void missingKeyFileRegeneratesThePair() throws Exception {
        FlociCertificateAuthority first = FlociCertificateAuthority.loadOrCreate(tempDir);
        Files.delete(tempDir.resolve("floci-root-ca.key"));

        FlociCertificateAuthority second = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertNotEquals(first.certificate(), second.certificate(), "a certificate without its key cannot sign");
        assertTrue(Files.exists(tempDir.resolve("floci-root-ca.key")));
        assertEquals(second.caPem(), Files.readString(tempDir.resolve("floci-root-ca.crt")));
    }

    @Test
    void aLeafInPlaceOfTheCaIsRegenerated() throws Exception {
        var leaf = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("another-ca"))
                .issueServerCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048, null);
        Files.writeString(tempDir.resolve("floci-root-ca.crt"), leaf.certificatePem());
        Files.writeString(tempDir.resolve("floci-root-ca.key"), leaf.privateKeyPem());

        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        assertTrue(ca.certificate().getBasicConstraints() >= 0, "must be a CA again");
        assertEquals(ca.certificate().getSubjectX500Principal(), ca.certificate().getIssuerX500Principal());
    }

    @Test
    void signsAServerLeafThatVerifiesAgainstTheCa() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate(
                "localhost", List.of("localhost", "*.localhost.floci.io"), KeyAlgorithm.RSA_2048, null);
        X509Certificate cert = parse(leaf.certificatePem());

        cert.verify(ca.certificate().getPublicKey());
        assertEquals(ca.certificate().getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertEquals(-1, cert.getBasicConstraints());
        assertEquals(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage());
        assertEquals(List.of("localhost", "*.localhost.floci.io"),
                cert.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
        assertTrue(ca.isIssuedByUs(cert));
    }

    @Test
    void signsAClientLeafWithClientAuthOnly() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        X509Certificate cert = parse(ca.issueClientCertificate("AWS IoT Certificate").certificatePem());

        cert.verify(ca.certificate().getPublicKey());
        assertEquals("CN=AWS IoT Certificate", cert.getSubjectX500Principal().getName());
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage());
        assertTrue(ca.isIssuedByUs(cert));
        assertEquals(java.time.Instant.parse("2049-12-31T23:59:59Z"), cert.getNotAfter().toInstant(), "AWS's fixed expiry");
        assertEquals(java.time.Instant.parse("2050-12-31T23:59:59Z"), ca.certificate().getNotAfter().toInstant(),
                "a new CA outlives the device certificates it signs");
    }

    @Test
    void expiryRulesStayValidAfterTheFixedDates() {
        java.time.Instant in2051 = java.time.Instant.parse("2051-06-01T00:00:00Z");
        assertEquals(in2051.plus(3650, java.time.temporal.ChronoUnit.DAYS), FlociCertificateAuthority.caNotAfter(in2051),
                "a CA created after 2050 lives ten years instead of being born expired");
        assertEquals(java.time.Instant.parse("2050-12-31T23:59:59Z"),
                FlociCertificateAuthority.caNotAfter(java.time.Instant.parse("2026-09-05T00:00:00Z")));

        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        assertEquals(ca.certificate().getNotAfter().toInstant(),
                ca.deviceCertificateNotAfter(java.time.Instant.parse("2050-06-01T00:00:00Z")),
                "after 2049 a device certificate lives a year, capped at the CA's expiry: never born expired");
        assertTrue(ca.deviceCertificateNotAfter(java.time.Instant.parse("2050-06-01T00:00:00Z"))
                .isAfter(java.time.Instant.parse("2050-06-01T00:00:00Z")));
        assertEquals(java.time.Instant.parse("2049-12-31T23:59:59Z"),
                ca.deviceCertificateNotAfter(java.time.Instant.parse("2026-09-05T00:00:00Z")));
    }

    @Test
    void deviceCertificateNeverOutlivesAShortLivedCa() throws Exception {
        CertificateGenerator gen = new CertificateGenerator();
        var shortLived = gen.generateCaCertificate(FlociCertificateAuthority.COMMON_NAME,
                java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS));
        Files.writeString(tempDir.resolve("floci-root-ca.crt"), shortLived.certificatePem());
        Files.writeString(tempDir.resolve("floci-root-ca.key"), shortLived.privateKeyPem());
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        assertEquals(parse(shortLived.certificatePem()), ca.certificate(), "the short-lived CA is kept as is");

        X509Certificate leaf = parse(ca.issueClientCertificate("device").certificatePem());
        X509Certificate fromCsr = ca.signClientCsr(csrPem("CN=device", rsaKeyPair(2048)));

        assertEquals(ca.certificate().getNotAfter(), leaf.getNotAfter(), "capped at the CA's expiry");
        assertEquals(ca.certificate().getNotAfter(), fromCsr.getNotAfter(), "the CSR path is capped the same way");
    }

    @Test
    void aSelfSignedLeafIsNotOurs() {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        var stranger = new CertificateGenerator().generateSelfSignedCertificate(
                "localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);

        assertFalse(ca.isIssuedByUs(parse(stranger.certificatePem())));
    }

    @Test
    void aLeafNamingOurCaButSignedByAnotherKeyIsNotOurs() {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        var impostor = new CertificateGenerator().generateCaCertificate(FlociCertificateAuthority.COMMON_NAME);
        var generator = new CertificateGenerator();
        var forged = generator.generateIssuedCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048, null,
                new CertificateGenerator.Issuer(parse(impostor.certificatePem()),
                        generator.parsePrivateKey(impostor.privateKeyPem())),
                CertificateGenerator.LeafUsage.SERVER);

        assertFalse(ca.isIssuedByUs(parse(forged.certificatePem())), "same issuer name, wrong signature");
    }

    @Test
    void signsACsrAsAClientLeafWithTheCsrSubjectAndKey() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        java.security.KeyPair deviceKey = rsaKeyPair(2048);

        X509Certificate cert = ca.signClientCsr(csrPem("CN=device-42,O=Example", deviceKey));

        cert.verify(ca.certificate().getPublicKey());
        assertEquals(deviceKey.getPublic(), cert.getPublicKey());
        assertEquals(new javax.security.auth.x500.X500Principal(
                new org.bouncycastle.asn1.x500.X500Name("CN=device-42,O=Example").getEncoded()), cert.getSubjectX500Principal());
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage());
        assertEquals(-1, cert.getBasicConstraints());
        assertTrue(ca.isIssuedByUs(cert));
    }

    @Test
    void refusesWhatIsNotACsr() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);

        var notPem = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ca.signClientCsr("hello"));
        assertTrue(notPem.getMessage().contains("certificateSigningRequest"), notPem.getMessage());
        var certificate = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ca.signClientCsr(ca.caPem()));
        assertTrue(certificate.getMessage().contains("not a PEM CERTIFICATE REQUEST"), certificate.getMessage());
    }

    @Test
    void refusesAWeakRsaKey() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tempDir);
        String weak = csrPem("CN=weak", rsaKeyPair(1024));

        var refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ca.signClientCsr(weak));
        assertTrue(refused.getMessage().contains("2048"), refused.getMessage());
    }

    private static java.security.KeyPair rsaKeyPair(int bits) throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(bits);
        return kpg.generateKeyPair();
    }

    private static String csrPem(String subject, java.security.KeyPair keyPair) throws Exception {
        var csr = new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(
                new org.bouncycastle.asn1.x500.X500Name(subject), keyPair.getPublic())
                .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate()));
        java.io.StringWriter out = new java.io.StringWriter();
        try (var writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(out)) {
            writer.writeObject(csr);
        }
        return out.toString();
    }

    private static X509Certificate parse(String pem) {
        return new CertificateGenerator().parseCertificate(pem);
    }
}
