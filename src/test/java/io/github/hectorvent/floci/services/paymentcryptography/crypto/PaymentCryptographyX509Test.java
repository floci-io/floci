package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-built X.509 wrapping certificate.
 *
 * <p>Java cannot mint certificates through a public API, so this asserts the thing that
 * makes the approach viable: that {@code CertificateFactory} parses the DER we build, and
 * that the public key it yields is the one we generated.
 */
class PaymentCryptographyX509Test {

    private static final String CN = "floci key-import KRD";

    @Test
    @DisplayName("CertificateFactory parses the hand-built DER and recovers the public key")
    void parsesWithCertificateFactory() throws Exception {
        KeyPair kp = X509.generateRsaKeyPair(2048);
        X509.Cert cert = X509.mintSelfSigned(kp, CN,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(7, ChronoUnit.DAYS));

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate parsed = (X509Certificate)
                cf.generateCertificate(new ByteArrayInputStream(cert.der));

        assertNotNull(parsed);
        assertEquals(kp.getPublic(), parsed.getPublicKey());
        assertEquals("SHA256withRSA", parsed.getSigAlgName());
    }

    @Test
    @DisplayName("the parsed certificate reports the common name and the validity window")
    void parsesSubjectAndValidity() throws Exception {
        KeyPair kp = X509.generateRsaKeyPair(2048);
        Instant notBefore = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant notAfter = Instant.now().plus(7, ChronoUnit.DAYS);
        X509.Cert cert = X509.mintSelfSigned(kp, CN, notBefore, notAfter);

        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.der));

        // getName() returns the RFC 2253 form, so the CN appears after "CN=".
        assertEquals("CN=" + CN, parsed.getSubjectX500Principal().getName());
        assertEquals("CN=" + CN, parsed.getIssuerX500Principal().getName());
        // The window is truncated to whole seconds by the UTCTime encoding.
        assertEquals(notBefore.getEpochSecond(), parsed.getNotBefore().toInstant().getEpochSecond());
        assertEquals(notAfter.getEpochSecond(), parsed.getNotAfter().toInstant().getEpochSecond());
        assertTrue(parsed.getNotAfter().after(parsed.getNotBefore()));
    }

    @Test
    @DisplayName("the validity window covers an import token timestamp issued just now")
    void validityCoversTokenTimestamp() throws Exception {
        KeyPair kp = X509.generateRsaKeyPair(2048);
        Instant now = Instant.now();
        X509.Cert cert = X509.mintSelfSigned(kp, CN,
                now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS));

        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.der));

        long tokenExpiry = now.plus(7, ChronoUnit.DAYS).getEpochSecond();
        assertTrue(parsed.getNotAfter().toInstant().getEpochSecond() >= tokenExpiry,
                "the certificate must outlive the import token it accompanies");
    }

    @Test
    @DisplayName("the certificate verifies against its own public key")
    void selfVerifies() throws Exception {
        KeyPair kp = X509.generateRsaKeyPair(2048);
        X509.Cert cert = X509.mintSelfSigned(kp, CN,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));

        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.der));
        parsed.verify(parsed.getPublicKey());
        parsed.checkValidity();
    }

    @Test
    @DisplayName("PEM round-trips and is wrapped at 64 characters")
    void pemRoundTrip() throws Exception {
        KeyPair kp = X509.generateRsaKeyPair(2048);
        X509.Cert cert = X509.mintSelfSigned(kp, CN,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));

        assertTrue(cert.pem.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(cert.pem.endsWith("-----END CERTIFICATE-----\n"));
        for (String line : cert.pem.split("\n")) {
            if (line.startsWith("-----")) {
                continue;
            }
            assertTrue(line.length() <= 64, "PEM line longer than 64 characters");
        }
        assertEquals(cert.base64(),
                Base64.getEncoder().encodeToString(X509.fromPem(cert.pem)));
    }

    @Test
    @DisplayName("two certificates over different keys differ")
    void distinctPerCall() {
        X509.Cert a = X509.mintSelfSigned(X509.generateRsaKeyPair(2048), CN,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        X509.Cert b = X509.mintSelfSigned(X509.generateRsaKeyPair(2048), CN,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        assertNotEquals(a.base64(), b.base64());
    }

    @Test
    @DisplayName("an unsupported key size is rejected")
    void rejectsBadKeySize() {
        assertThrows(IllegalArgumentException.class, () -> X509.generateRsaKeyPair(1024));
        assertThrows(IllegalArgumentException.class, () -> X509.generateRsaKeyPair(512));
    }

    @Test
    @DisplayName("the serial number is a positive DER integer")
    void serialIsPositive() {
        byte[] b = new byte[] {0x00, 0x00, (byte) 0xFF, (byte) 0x80, 0x01};
        byte[] der = X509.derInteger(b);
        // Leading zeros stripped, and a zero byte prepended for the high bit.
        assertEquals("00ff8001", Hex.encode(der));
        // A value that is already positive and non-zero is untouched.
        assertEquals("7f", Hex.encode(X509.derInteger(new byte[] {0x7F})));
    }
}
