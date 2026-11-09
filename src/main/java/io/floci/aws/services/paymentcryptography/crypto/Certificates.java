package io.floci.aws.services.paymentcryptography.crypto;

import io.floci.aws.core.common.CertificateMaterialException;
import io.floci.aws.core.common.Pem;
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * The certificates the key exchange hands out and reads.
 *
 * <p>The service gives a counterparty a certificate over a key pair it made for the occasion,
 * and reads the certificate a counterparty brings. A certificate travels in three shapes: PEM,
 * base64 of PEM (which is how the service returns one), and base64 of DER.
 *
 * <p>This is the Payment Cryptography service's own helper rather than a shared one: the
 * emulator already has {@link Pem} for reading certificates and ACM's own generator for
 * minting them, and neither offers what the key exchange needs, which is a certificate over
 * an arbitrary key pair with a chosen common name and a chosen validity window.
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
     * Mint a self-signed certificate over the given key pair.
     *
     * <p>The issuer is the subject, so the certificate vouches for itself: the two parties in
     * the exchange trust each other's copy of it, which is what the emulator's certificates are
     * for.
     *
     * @param keyPair the key pair the certificate is over
     * @param commonName the common name the certificate carries
     * @param notBefore when the certificate becomes valid
     * @param notAfter when it stops being valid
     * @param random the randomness the serial number is made from
     * @return the certificate in DER and in PEM
     */
    public static Minted mintSelfSigned(KeyPair keyPair, String commonName,
                                         Instant notBefore, Instant notAfter, SecureRandom random) {
        return mint(keyPair.getPublic(), commonName, keyPair.getPrivate(), commonName,
                notBefore, notAfter, random);
    }

    /**
     * Mint a certificate over a key pair, signed by an issuer.
     *
     * <p>The issuer signs the certificate, so a party that trusts the issuer's public key
     * trusts the certificate: that is how the key exchange's certificate authority vouches
     * for the other party's certificate.
     *
     * @param subjectPublicKey the public key the certificate is over
     * @param subjectCommonName the common name of the subject
     * @param issuerPrivateKey the key that signs the certificate
     * @param issuerCommonName the common name of the issuer
     * @param notBefore when the certificate becomes valid
     * @param notAfter when it stops being valid
     * @param random the randomness the serial number is made from
     * @return the certificate in DER and in PEM
     */
    public static Minted mint(PublicKey subjectPublicKey, String subjectCommonName,
                               PrivateKey issuerPrivateKey, String issuerCommonName,
                               Instant notBefore, Instant notAfter, SecureRandom random) {
        X500Name subject = new X500Name("CN=" + subjectCommonName);
        X500Name issuer = new X500Name("CN=" + issuerCommonName);
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
                    new BigInteger(1, random).setBit(127), Date.from(notBefore), Date.from(notAfter),
                    subject, subjectPublicKey);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm(issuerPrivateKey))
                    .build(issuerPrivateKey);
            X509CertificateHolder holder = builder.build(signer);
            byte[] der = new JcaX509CertificateConverter().getCertificate(holder).getEncoded();
            return new Minted(der, toPem(der));
        } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
            throw new IllegalStateException("cannot mint a certificate", e);
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

    /** The signature algorithm the certificate is signed with: SHA-256 over the key's own kind. */
    private static String signatureAlgorithm(PrivateKey issuerKey) {
        return issuerKey instanceof ECKey ? "SHA256withECDSA" : "SHA256withRSA";
    }
}
