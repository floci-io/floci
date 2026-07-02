package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the distinction between the two certificate flavors:
 * <ul>
 *   <li>{@code generateCertificate} mimics an ACM-issued cert (cosmetic Amazon issuer, not a CA)
 *       — unchanged legacy behavior, not verifiable as a trust anchor.</li>
 *   <li>{@code generateSelfSignedCertificate} is genuinely self-signed (issuer == subject) and a
 *       CA, so a client that trusts it can verify a TLS connection presenting it. This is what
 *       lets Floci's HTTPS endpoint be trusted by Lambda containers (CDK cfn-response callbacks).</li>
 * </ul>
 */
class CertificateGeneratorSelfSignedTest {

    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @Test
    void selfSignedCertificateIsItsOwnIssuerAndACa() throws Exception {
        var generated = generator.generateSelfSignedCertificate(
                "localhost", List.of("localhost", "localhost.floci.io"), KeyAlgorithm.RSA_2048);

        X509Certificate cert = generator.parseCertificate(generated.certificatePem());

        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal(),
                "self-signed cert must have issuer == subject so it is a valid trust anchor");
        assertTrue(cert.getBasicConstraints() >= 0, "self-signed cert must be marked as a CA");
        assertTrue(cert.getKeyUsage() != null && cert.getKeyUsage()[5],
                "self-signed cert must assert keyCertSign so it can be its own issuer");

        // Sanity: the self-signature verifies against the cert's own public key.
        cert.verify(cert.getPublicKey());
    }

    @Test
    void acmStyleCertificateKeepsCosmeticAmazonIssuerAndIsNotACa() throws Exception {
        var generated = generator.generateCertificate(
                "localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);

        X509Certificate cert = generator.parseCertificate(generated.certificatePem());

        assertNotEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal(),
                "ACM-style cert keeps a cosmetic Amazon issuer distinct from the subject");
        assertTrue(cert.getIssuerX500Principal().getName().contains("Amazon"),
                "ACM-style issuer should still be the Amazon CA DN");
        assertEquals(-1, cert.getBasicConstraints(), "ACM-style leaf cert must not be a CA");
    }
}
