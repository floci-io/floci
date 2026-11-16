package io.floci.aws.services.paymentcryptography.crypto;

import io.floci.aws.core.common.CertificateMaterialException;
import io.floci.aws.core.common.Pem;
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * The certificates the key exchange hands out and reads.
 *
 * <p>The service gives a counterparty a certificate over a key pair it made for the occasion,
 * and reads the certificate a counterparty brings. A certificate travels in three shapes: PEM,
 * base64 of PEM (which is how the service returns one), and base64 of DER.
 *
 * <p>Reading a certificate is the emulator's own business, through {@link Pem}; minting one
 * belongs to ACM's generator, which the service asks for a certificate with.
 */
public final class Certificates {

    private Certificates() {
    }

    /** A minted certificate, in the two forms the service uses it in. */
    public record Minted(byte[] der, String pem) {
    }

    /**
     * Parse a certificate the service hands over, or one a counterparty brings.
     *
     * <p>PEM, base64 of PEM and base64 of DER are all accepted: the service returns a
     * certificate as base64 of its PEM, and a client certificate arrives the same way.
     *
     * @param pemOrBase64 the certificate, in any of those shapes
     * @return the parsed certificate
     * @throws IllegalArgumentException if the text is not a certificate in any of them
     */
    public static X509Certificate parseCertificate(String pemOrBase64) {
        String text = pemOrBase64 == null ? "" : pemOrBase64.trim();
        if (text.contains("-----BEGIN")) {
            return readPem(text);
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the certificate is neither PEM nor base64");
        }
        String decodedText = new String(decoded, StandardCharsets.UTF_8);
        // base64 of a PEM, which is how the service returns one, or base64 of the DER itself.
        if (decodedText.contains("-----BEGIN")) {
            return readPem(decodedText);
        }
        return readDer(decoded);
    }

    /** The certificate a DER document holds. */
    private static X509Certificate readDer(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the certificate does not parse: " + e.getMessage());
        }
    }

    /** The certificate a PEM document holds, read by the emulator's own PEM reader. */
    private static X509Certificate readPem(String pem) {
        try {
            return Pem.parseCertificate(pem);
        } catch (CertificateMaterialException e) {
            throw new IllegalArgumentException("the certificate does not parse: " + e.getMessage());
        }
    }

    /**
     * The identifier a TR-34 block uses to name its recipient.
     *
     * <p>A RecipientInfo carries the receiver's certificate identifier: an
     * IssuerAndSerialNumber, which is the issuer's name and the certificate's serial number.
     * Both are taken from the certificate itself, so the identifier is the one the receiver's
     * own certificate carries.
     *
     * @param certDer the certificate in DER
     * @return the issuer and serial number, as the DER of an IssuerAndSerialNumber
     */
    public static byte[] certificateId(byte[] certDer) {
        X509Certificate certificate = readDer(certDer);
        try {
            return new IssuerAndSerialNumber(
                    new X500Name(certificate.getIssuerX500Principal().getName()),
                    certificate.getSerialNumber()).getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode the certificate identifier", e);
        }
    }

    /**
     * The certificate in DER.
     *
     * @param certificate the certificate
     * @return the certificate in DER
     */
    public static byte[] toDer(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("the certificate does not encode: " + e.getMessage());
        }
    }

    /**
     * Wrap DER as a PEM document.
     *
     * @param der the certificate in DER
     * @return the certificate in PEM
     */
    public static String toPem(byte[] der) {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(new PemObject("CERTIFICATE", der));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the certificate as PEM", e);
        }
        return writer.toString();
    }

    /**
     * A PEM certificate as base64, which is how the service returns one.
     *
     * @param pem the certificate in PEM
     * @return the base64 of the PEM
     */
    public static String pemBase64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }

}
