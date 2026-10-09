package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading half of certificate handling, which previously had no test of its own: it was only
 * ever exercised through ACM's generator. Generated material is the input here because that is what
 * every caller feeds it, and what a round trip has to survive.
 */
class PemTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static CertificateGenerator.GeneratedCertificate generated() {
        return GENERATOR.generateSelfSignedCertificate("pem.test.local", List.of(),
                KeyAlgorithm.RSA_2048);
    }

    @Test
    void readsBackACertificateItWasGiven() {
        CertificateGenerator.GeneratedCertificate material = generated();
        X509Certificate certificate = Pem.parseCertificate(material.certificatePem());
        assertEquals("CN=pem.test.local", certificate.getSubjectX500Principal().getName());
    }

    @Test
    void readsBackAPrivateKeyItWasGiven() {
        CertificateGenerator.GeneratedCertificate material = generated();
        PrivateKey key = Pem.parsePrivateKey(material.privateKeyPem());
        assertEquals("RSA", key.getAlgorithm());
    }

    /** The check IAM's server-certificate upload and the CA's startup both depend on. */
    @Test
    void recognisesAMatchingKeyPair() throws Exception {
        CertificateGenerator.GeneratedCertificate material = generated();
        assertTrue(Pem.isPair(Pem.parsePrivateKey(material.privateKeyPem()),
                Pem.parseCertificate(material.certificatePem()).getPublicKey()));
    }

    @Test
    void rejectsAKeyFromADifferentCertificate() throws Exception {
        CertificateGenerator.GeneratedCertificate one = generated();
        CertificateGenerator.GeneratedCertificate other = generated();
        assertFalse(Pem.isPair(Pem.parsePrivateKey(one.privateKeyPem()),
                Pem.parseCertificate(other.certificatePem()).getPublicKey()));
    }

    /** EC keys take the other signature algorithm, so the pair check has to hold for them too. */
    @Test
    void recognisesAMatchingEcKeyPair() throws Exception {
        CertificateGenerator.GeneratedCertificate material = GENERATOR.generateSelfSignedCertificate(
                "ec.pem.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        assertTrue(Pem.isPair(Pem.parsePrivateKey(material.privateKeyPem()),
                Pem.parseCertificate(material.certificatePem()).getPublicKey()));
    }

    @Test
    void refusesSomethingThatIsNotACertificate() {
        assertThrows(CertificateMaterialException.class, () -> Pem.parseCertificate("not a pem at all"));
        assertThrows(CertificateMaterialException.class, () -> Pem.parseCertificate(""));
    }

    @Test
    void refusesSomethingThatIsNotAPrivateKey() {
        assertThrows(CertificateMaterialException.class, () -> Pem.parsePrivateKey("not a pem at all"));
        // A certificate is valid PEM, but it is not a private key.
        String certificatePem = generated().certificatePem();
        assertThrows(CertificateMaterialException.class, () -> Pem.parsePrivateKey(certificatePem));
    }
}
