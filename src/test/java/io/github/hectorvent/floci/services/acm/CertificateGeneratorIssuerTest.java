package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issuer-signing primitive: a CA that can sign, leaves that verify against it, and the
 * Extended Key Usage split between server and client leaves.
 */
class CertificateGeneratorIssuerTest {

    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    private static CertificateGenerator.Issuer newIssuer() {
        var ca = generator.generateCaCertificate("Floci Local CA");
        return new CertificateGenerator.Issuer(
                generator.parseCertificate(ca.certificatePem()), generator.parsePrivateKey(ca.privateKeyPem()));
    }

    @Test
    void caCertificateIsSelfSignedAndCanSign() throws Exception {
        var ca = generator.generateCaCertificate("Floci Local CA");
        X509Certificate cert = generator.parseCertificate(ca.certificatePem());

        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertEquals("CN=Floci Local CA", cert.getSubjectX500Principal().getName());
        assertTrue(cert.getBasicConstraints() >= 0, "CA must carry cA=true");
        assertTrue(cert.getKeyUsage()[5], "CA must assert keyCertSign");
        assertTrue(cert.getKeyUsage()[6], "CA must assert cRLSign");
        assertNull(cert.getExtendedKeyUsage(), "a CA carries no EKU");
        assertNull(cert.getSubjectAlternativeNames(), "a CA carries no SAN");
        cert.verify(cert.getPublicKey());
        assertEquals(ca.subject(), ca.issuer());
    }

    @Test
    void issuedLeafVerifiesAgainstIssuerAndCarriesServerAuth() throws Exception {
        var issuer = newIssuer();

        var leaf = generator.generateIssuedCertificate("api.example.test", List.of("*.example.test"),
                KeyAlgorithm.RSA_2048, null, issuer, CertificateGenerator.LeafUsage.SERVER);
        X509Certificate cert = generator.parseCertificate(leaf.certificatePem());

        assertEquals(issuer.certificate().getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertNotEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertEquals(-1, cert.getBasicConstraints(), "leaf must not be a CA");
        assertFalse(cert.getKeyUsage()[5], "leaf must not assert keyCertSign");
        assertTrue(cert.getKeyUsage()[0] && cert.getKeyUsage()[2], "RSA leaf: digitalSignature and keyEncipherment");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage(),
                "serverAuth and clientAuth, as ACM issues and DescribeCertificate advertises");
        assertEquals(List.of("api.example.test", "*.example.test"),
                cert.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
        assertEquals("SHA512WITHRSA", leaf.signatureAlgorithm(), "ACM spells the algorithm in upper case");
        assertTrue(cert.getSigAlgName().equalsIgnoreCase(leaf.signatureAlgorithm()),
                "metadata reports the real signature algorithm: " + cert.getSigAlgName());
        assertTrue(leaf.serial().matches("([0-9a-f]{2}:)+[0-9a-f]{2}"),
                "colon-separated hex serial: " + leaf.serial());
        assertEquals(cert.getSerialNumber(), new BigInteger(leaf.serial().replace(":", ""), 16));
        assertEquals(cert.getNotBefore().toInstant(), leaf.notBefore());
        assertEquals(cert.getNotAfter().toInstant(), leaf.notAfter());
        assertEquals("CN=api.example.test", leaf.subject());
        assertEquals(issuer.certificate().getSubjectX500Principal().getName(), leaf.issuer());
        cert.verify(issuer.certificate().getPublicKey());
    }

    @Test
    void issuedLeafReusesSuppliedKeyPairAndClientUsage() throws Exception {
        var issuer = newIssuer();
        var first = generator.generateIssuedCertificate("device-1", List.of(), KeyAlgorithm.RSA_2048, null, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        var keyPair = new KeyPair(
                generator.parseCertificate(first.certificatePem()).getPublicKey(),
                generator.parsePrivateKey(first.privateKeyPem()));

        var second = generator.generateIssuedCertificate("device-1", List.of(), KeyAlgorithm.RSA_2048, keyPair, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        X509Certificate cert = generator.parseCertificate(second.certificatePem());

        assertEquals(keyPair.getPublic(), cert.getPublicKey(), "supplied key pair must be reused");
        assertEquals(first.privateKeyPem(), second.privateKeyPem(), "the same private key is returned");
        assertNotEquals(first.serial(), second.serial(), "every issue gets a fresh serial");
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage(), "clientAuth only");
        cert.verify(issuer.certificate().getPublicKey());
    }

    @Test
    void ecLeafSignedByRsaCaReportsTheIssuerAlgorithmAndNoKeyEncipherment() throws Exception {
        var issuer = newIssuer();

        var leaf = generator.generateIssuedCertificate("ec.example.test", null, KeyAlgorithm.EC_prime256v1, null,
                issuer, CertificateGenerator.LeafUsage.SERVER);
        X509Certificate cert = generator.parseCertificate(leaf.certificatePem());

        assertEquals("EC", cert.getPublicKey().getAlgorithm());
        assertEquals("SHA512WITHRSA", leaf.signatureAlgorithm(), "the signature is the RSA issuer's");
        assertTrue(cert.getKeyUsage()[0], "digitalSignature");
        assertFalse(cert.getKeyUsage()[2], "an EC key never enciphers");
        assertEquals(List.of("ec.example.test"), cert.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
        cert.verify(issuer.certificate().getPublicKey());
    }

    @Test
    void anIssuerWhoseKeyDoesNotMatchItsCertificateIsRefused() {
        var one = generator.generateCaCertificate("One");
        var other = generator.generateCaCertificate("Other");
        var mismatched = new CertificateGenerator.Issuer(
                generator.parseCertificate(one.certificatePem()), generator.parsePrivateKey(other.privateKeyPem()));

        var refused = org.junit.jupiter.api.Assertions.assertThrows(CertificateGenerationException.class,
                () -> generator.generateIssuedCertificate("x.example.test", List.of(), KeyAlgorithm.RSA_2048, null,
                        mismatched, CertificateGenerator.LeafUsage.SERVER));
        assertTrue(refused.getMessage().contains("Signature"), refused.getMessage());
    }

    @Test
    void aSuppliedKeyPairThatIsNotAPairIsRefused() {
        var issuer = newIssuer();
        var a = generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.RSA_2048, null, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        var b = generator.generateIssuedCertificate("b", List.of(), KeyAlgorithm.RSA_2048, null, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        var notAPair = new KeyPair(generator.parseCertificate(a.certificatePem()).getPublicKey(),
                generator.parsePrivateKey(b.privateKeyPem()));

        var refused = org.junit.jupiter.api.Assertions.assertThrows(CertificateGenerationException.class,
                () -> generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.RSA_2048, notAPair, issuer,
                        CertificateGenerator.LeafUsage.CLIENT));
        assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
    }

    @Test
    void aSuppliedKeyPairOfAnotherAlgorithmIsRefused() {
        var issuer = newIssuer();
        var rsa = generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.RSA_2048, null, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        var rsaPair = new KeyPair(generator.parseCertificate(rsa.certificatePem()).getPublicKey(),
                generator.parsePrivateKey(rsa.privateKeyPem()));

        var refused = org.junit.jupiter.api.Assertions.assertThrows(CertificateGenerationException.class,
                () -> generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.EC_prime256v1, rsaPair, issuer,
                        CertificateGenerator.LeafUsage.CLIENT));
        assertTrue(refused.getMessage().contains("not the requested EC_prime256v1"), refused.getMessage());
    }

    @Test
    void aSuppliedEcKeyPairOnACurveOfTheSameSizeIsRefused() throws Exception {
        var issuer = newIssuer();
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256k1"));
        KeyPair k1 = kpg.generateKeyPair();
        kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair p256 = kpg.generateKeyPair();

        var refused = org.junit.jupiter.api.Assertions.assertThrows(CertificateGenerationException.class,
                () -> generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.EC_prime256v1, k1, issuer,
                        CertificateGenerator.LeafUsage.CLIENT));
        assertTrue(refused.getMessage().contains("not the requested EC_prime256v1"), refused.getMessage());
        var accepted = generator.generateIssuedCertificate("a", List.of(), KeyAlgorithm.EC_prime256v1, p256, issuer,
                CertificateGenerator.LeafUsage.CLIENT);
        assertEquals(p256.getPublic(), generator.parseCertificate(accepted.certificatePem()).getPublicKey());
    }

    @Test
    void duplicateSansAreWrittenOnce() throws Exception {
        var leaf = generator.generateIssuedCertificate("dup.example.test", List.of("dup.example.test", "other.example.test",
                "other.example.test"), KeyAlgorithm.RSA_2048, null, newIssuer(), CertificateGenerator.LeafUsage.SERVER);
        X509Certificate cert = generator.parseCertificate(leaf.certificatePem());

        assertEquals(List.of("dup.example.test", "other.example.test"),
                cert.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
    }

    @Test
    void existingSelfSignedPathIsUnchanged() throws Exception {
        var selfSigned = generator.generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        X509Certificate ss = generator.parseCertificate(selfSigned.certificatePem());
        assertEquals(ss.getSubjectX500Principal(), ss.getIssuerX500Principal());
        assertTrue(ss.getBasicConstraints() >= 0);
        assertNull(ss.getExtendedKeyUsage(), "the self-signed trust anchor carries no EKU, as before");
        assertEquals(selfSigned.subject(), selfSigned.issuer());
        assertTrue(selfSigned.serial().matches("([0-9a-f]{2}:)+[0-9a-f]{2}"));
        ss.verify(ss.getPublicKey());
    }

    @Test
    void ecPrivateKeysAreWrittenAsPkcs8AndReadBack() throws Exception {
        var ec = generator.generateIssuedCertificate("ec.example.test", List.of(), KeyAlgorithm.EC_secp384r1, null,
                newIssuer(), CertificateGenerator.LeafUsage.SERVER);
        var rsa = generator.generateIssuedCertificate("rsa.example.test", List.of(), KeyAlgorithm.RSA_2048, null,
                newIssuer(), CertificateGenerator.LeafUsage.CLIENT);

        assertTrue(ec.privateKeyPem().startsWith("-----BEGIN PRIVATE KEY-----"), "PKCS#8 carries the curve");
        assertTrue(rsa.privateKeyPem().startsWith("-----BEGIN RSA PRIVATE KEY-----"), "RSA stays PKCS#1, as AWS IoT hands out");
        assertTrue(CertificateGenerator.isPair(generator.parsePrivateKey(ec.privateKeyPem()),
                generator.parseCertificate(ec.certificatePem()).getPublicKey()), "the EC key reads back and matches");
    }

    @Test
    void colonHexPadsOddLengthAndSplitsBytes() {
        assertEquals("0a", CertificateGenerator.colonHex(BigInteger.TEN));
        assertEquals("01:00", CertificateGenerator.colonHex(BigInteger.valueOf(256)));
        assertEquals("ff:ff:ff", CertificateGenerator.colonHex(new BigInteger("ffffff", 16)));
    }
}
