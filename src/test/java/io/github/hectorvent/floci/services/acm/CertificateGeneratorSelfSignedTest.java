package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code generateSelfSignedCertificate} is genuinely self-signed (issuer == subject) and a CA, so
 * a client that trusts it can verify a TLS connection presenting it. Leaves that clients must
 * validate are issued by a CA through {@code generateIssuedCertificate} instead.
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
}
