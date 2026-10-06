package io.github.hectorvent.floci.services.ses;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Self-signed certificates whose SAN carries an email address, which ACM's own generator cannot mint. */
final class SmimeTestCertificates {

    record Pem(String certificate, String privateKey) {}

    /** The extensions SES checks besides the SAN; {@link #EMAIL_SIGNING} is the shape it accepts. */
    record Extensions(Boolean ca, int keyUsage, KeyPurposeId... extendedKeyUsage) {}

    static final Extensions EMAIL_SIGNING =
            new Extensions(false, KeyUsage.digitalSignature, KeyPurposeId.id_kp_emailProtection);

    // Well before the MutableClock the Quarkus tests run on, so a certificate is already valid there.
    private static final Instant NOT_BEFORE = Instant.parse("2020-01-01T00:00:00Z");

    private SmimeTestCertificates() {
    }

    static Pem rsa(String email, int keySize) {
        return rsa(email, keySize, EMAIL_SIGNING);
    }

    static Pem rsa(String email, int keySize, Extensions extensions) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize);
            return create(email, generator.generateKeyPair(), "SHA256withRSA", extensions);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an RSA key for " + email, e);
        }
    }

    static Pem ec(String email, String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return create(email, generator.generateKeyPair(), "SHA256withECDSA", EMAIL_SIGNING);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an EC key for " + email, e);
        }
    }

    static Pem ed25519(String email) {
        try {
            return create(email, KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), "Ed25519",
                    EMAIL_SIGNING);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an Ed25519 key for " + email, e);
        }
    }

    private static Pem create(String email, KeyPair keyPair, String signatureAlgorithm, Extensions extensions) {
        try {
            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + email);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    BigInteger.valueOf(now.toEpochMilli()), Date.from(NOT_BEFORE),
                    Date.from(now.plus(Duration.ofDays(365))), subject, keyPair.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.rfc822Name, email)));
            if (extensions.ca() != null) {
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(extensions.ca()));
            }
            if (extensions.keyUsage() != 0) {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(extensions.keyUsage()));
            }
            if (extensions.extendedKeyUsage().length > 0) {
                builder.addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(extensions.extendedKeyUsage()));
            }
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new Pem(pem(holder), pem(keyPair.getPrivate()));
        } catch (IOException | OperatorCreationException e) {
            throw new IllegalStateException("Could not generate a test certificate for " + email, e);
        }
    }

    private static String pem(Object object) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }
}
