package io.floci.aws.services.paymentcryptography.crypto;

import io.floci.aws.services.acm.CertificateGenerator;
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The certificates the key exchange hands out and reads.
 *
 * <p>The identifier a TR-34 block names its recipient with is the certificate's own issuer
 * and serial number, so the two are read back from the certificate the service minted and
 * compared with what the identifier carries.
 */
public class CertificatesTest {

    private static final String CN = "floci key-exchange";

    @Test
    void theIdentifierOfACertificateIsItsIssuerAndItsSerial() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Instant now = Instant.now();
        Certificates.Minted certificate = mint(keyPair.getPublic(), CN, keyPair.getPrivate(), CN);

        byte[] identifier = Certificates.certificateId(certificate.der());

        assertNotNull(identifier);
        IssuerAndSerialNumber parsed = IssuerAndSerialNumber.getInstance(identifier);
        X509Certificate certificateParsed = Certificates.parseCertificate(certificate.pem());
        assertEquals(certificateParsed.getSerialNumber(),
                parsed.getCertificateSerialNumber().getValue());
        assertEquals(certificateParsed.getIssuerX500Principal().getName(),
                parsed.getName().toString());
    }

    @Test
    void theIdentifierOfACertificateThatDoesNotParseIsRefused() {
        try {
            Certificates.certificateId(new byte[] {0x30, 0x03, 0x02, 0x01, 0x05});
        } catch (RuntimeException refused) {
            assertEquals(true, refused.getMessage().contains("does not parse"));
            return;
        }
        throw new AssertionError("a certificate that does not parse was accepted");
    }

    @Test
    void theSerialOfTheIdentifierIsTheCertificatesOwn() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Instant now = Instant.now();
        Certificates.Minted certificate = mint(keyPair.getPublic(), CN, keyPair.getPrivate(), CN);

        BigInteger serial = IssuerAndSerialNumber
                .getInstance(Certificates.certificateId(certificate.der()))
                .getCertificateSerialNumber().getValue();

        assertEquals(Certificates.parseCertificate(certificate.pem()).getSerialNumber(), serial);
    }

    /**
     * A certificate over a key pair, signed by an issuer.
     *
     * <p>The service mints its certificates through ACM's generator, and a test that needs a
     * certificate of its own mints it the same way.
     */
    public static Certificates.Minted mint(PublicKey subjectKey, String subjectName,
                                          PrivateKey issuerKey, String issuerName) {
        return mint(subjectKey, subjectName, issuerKey, issuerName,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));
    }

    /** A certificate over a key pair, signed by an issuer, with the given validity window. */
    public static Certificates.Minted mint(PublicKey subjectKey, String subjectName,
                                          PrivateKey issuerKey, String issuerName,
                                          Instant notBefore, Instant notAfter) {
        CertificateGenerator generator = new CertificateGenerator();
        try {
            X509Certificate certificate = generator.signCertificate(
                    new X500Name("CN=" + subjectName), subjectKey,
                    new X500Name("CN=" + issuerName), issuerKey,
                    List.of(), false, null, notBefore, notAfter);
            byte[] der = certificate.getEncoded();
            return new Certificates.Minted(der, Certificates.toPem(der));
        } catch (Exception e) {
            throw new IllegalStateException("cannot mint a certificate", e);
        }
    }
}
