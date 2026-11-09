package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The certificates the key exchange hands out and reads.
 *
 * <p>The identifier a TR-34 block names its recipient with is the certificate's own issuer
 * and serial number, so the two are read back from the certificate the service minted and
 * compared with what the identifier carries.
 */
class CertificatesTest {

    private static final String CN = "floci key-exchange";

    @Test
    void theIdentifierOfACertificateIsItsIssuerAndItsSerial() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Instant now = Instant.now();
        Certificates.Minted certificate = Certificates.mintSelfSigned(keyPair, CN,
                now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS), new SecureRandom());

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
        Certificates.Minted certificate = Certificates.mintSelfSigned(keyPair, CN,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), new SecureRandom());

        BigInteger serial = IssuerAndSerialNumber
                .getInstance(Certificates.certificateId(certificate.der()))
                .getCertificateSerialNumber().getValue();

        assertEquals(Certificates.parseCertificate(certificate.pem()).getSerialNumber(), serial);
    }
}
