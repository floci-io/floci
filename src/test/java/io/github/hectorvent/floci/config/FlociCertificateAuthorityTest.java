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
        var leaf = new CertificateGenerator().generateCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048);
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

    private static X509Certificate parse(String pem) {
        return new CertificateGenerator().parseCertificate(pem);
    }
}
