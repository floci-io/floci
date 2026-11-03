package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * X.509 v3 certificate minting.
 *
 * <p>This is the service's own stand-in for the AWS-issued key-import
 * wrapping certificate returned by {@code GetParametersForImport}. Java has a
 * public API for <em>parsing</em> certificates but not for minting one, so the
 * DER is built by hand and signed with {@code SHA256withRSA}, then handed to
 * {@code CertificateFactory} in a test to prove it really parses.
 */
public final class X509 {

    /** 2.5.4.3 commonName, pre-encoded. */
    private static final byte[] OID_CN = Hex.decode("0603550403");
    /** sha256WithRSAEncryption, pre-encoded. */
    private static final byte[] ALGID_SHA256_RSA = Hex.decode("300D06092A864886F70D01010B0500");
    /** ecdsa-with-SHA256, pre-encoded. */
    private static final byte[] ALGID_SHA256_ECDSA = Hex.decode("300A06082A8648CE3D040302");

    private X509() {
    }

    /** A minted certificate. */
    public static final class Cert {
        public final byte[] der;
        public final String pem;

        Cert(byte[] der) {
            this.der = der;
            String b64 = Base64.getEncoder().encodeToString(der);
            StringBuilder sb = new StringBuilder();
            sb.append("-----BEGIN CERTIFICATE-----\n");
            for (int i = 0; i < b64.length(); i += 64) {
                sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
            }
            sb.append("-----END CERTIFICATE-----\n");
            this.pem = sb.toString();
        }

        /** The certificate as base64 of its DER. */
        public String base64() {
            return Base64.getEncoder().encodeToString(der);
        }

        /**
         * The certificate as base64 of its PEM, which is how the service
         * returns it.
         *
         * <p>The service sends the certificate in PEM format, base64 encoded.
         * A caller decodes it and gets the PEM back.
         */
        public String pemBase64() {
            return Base64.getEncoder().encodeToString(
                    pem.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * Parse a certificate the service hands over.
     *
     * <p>The service returns a certificate as base64 of its PEM, and a client
     * certificate arrives the same way, so PEM, base64 of PEM and base64 of DER
     * are all accepted. The JDK parses the DER, which also gives the subject
     * public key and the ability to verify the signature.
     *
     * @param pemOrBase64 the certificate, in any of those shapes
     * @return the parsed certificate
     */
    public static X509Certificate parseCertificate(String pemOrBase64) {
        String text = pemOrBase64 == null ? "" : pemOrBase64.trim();
        byte[] der;
        if (text.contains("-----BEGIN")) {
            der = fromPem(text);
        } else {
            byte[] decoded;
            try {
                decoded = Base64.getMimeDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the certificate is neither PEM nor base64");
            }
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (decodedText.contains("-----BEGIN")) {
                // base64 of a PEM, which is how the service returns one.
                der = fromPem(decodedText);
            } else {
                // base64 of the DER itself.
                der = decoded;
            }
        }
        try {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the certificate does not parse: " + e.getMessage());
        }
    }

    /**
     * The randomness the key pairs are made from.
     *
     * <p>One instance, shared. It is not the strong instance: on a machine
     * whose strong source is {@code /dev/random}, asking for it can block on
     * entropy, and a request that mints a key pair should not hang waiting
     * for the pool to fill. The default instance is seeded from the same
     * system sources and does not block.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generate an RSA key pair of the requested modulus size. */
    public static KeyPair generateRsaKeyPair(int bits) {
        if (bits != 2048 && bits != 3072 && bits != 4096) {
            throw new IllegalArgumentException(
                    "unsupported wrapping key size " + bits + " (2048, 3072, 4096)");
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(bits, RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an RSA key pair", e);
        }
    }

    /** Mint a self-signed certificate over the given key pair. */
    public static Cert mintSelfSigned(KeyPair keyPair, String commonName,
                                      Instant notBefore, Instant notAfter) {
        return mint(keyPair.getPublic(), commonName, keyPair.getPrivate(), commonName,
                notBefore, notAfter);
    }

    /**
     * The identifier a TR-34 block uses to name its recipient.
     *
     * <p>A RecipientInfo carries the receiver's certificate identifier: an
     * IssuerAndSerialNumber, which is the issuer's name and the certificate's
     * serial number. Both are taken from the certificate itself, so the
     * identifier is the one the receiver's own certificate carries.
     *
     * @param certDer the certificate in DER
     * @return the issuer and serial number, as a SEQUENCE
     */
    public static Asn1.Node certificateId(byte[] certDer) {
        List<Asn1.Node> certificate = Asn1.parse(certDer);
        List<Asn1.Node> tbs = certificate.get(0).children().get(0).children();
        // [ [0] version, serialNumber, signature, issuer, validity, subject, ... ]
        int at = tbs.get(0).tag == 0xa0 ? 1 : 0;
        Asn1.Node serial = tbs.get(at);
        Asn1.Node issuer = tbs.get(at + 2);
        if (serial.tag != 0x02 || issuer.tag != 0x30) {
            throw new IllegalArgumentException(
                    "the certificate does not carry a serial number and an issuer");
        }
        return Asn1.seq(issuer, serial);
    }

    /**
     * Generate an EC key pair on the curve the algorithm names.
     *
     * <p>The curve is the NIST one the cardholder-device flow uses.
     *
     * @param curve the curve's name, such as {@code secp256r1}
     */
    public static KeyPair generateEcKeyPair(String curve) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec(curve), RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an EC key pair on " + curve, e);
        }
    }

    /**
     * Mint an X.509 v3 certificate over {@code subjectPublicKey}, signed by
     * {@code issuerPrivateKey} with SHA-256 and RSA. For a self-signed
     * certificate, pass the subject's own key as the issuer key and the same
     * common name.
     */
    public static Cert mint(PublicKey subjectPublicKey, String subjectCn,
                            PrivateKey issuerPrivateKey, String issuerCn,
                            Instant notBefore, Instant notAfter) {
        // For an RSA key, getEncoded() is exactly the X.509 SubjectPublicKeyInfo
        // DER that the encoding needs, and it is a complete TLV, so it goes in
        // the TBS as a raw pre-encoded element rather than being rebuilt. The
        // same is true of an EC key, whose encoding is also a SubjectPublicKeyInfo.
        byte[] spki = subjectPublicKey.getEncoded();
        byte[] algId = ec(issuerPrivateKey) ? ALGID_SHA256_ECDSA : ALGID_SHA256_RSA;

        byte[] tbs = Asn1.encodeOne(Asn1.seq(
                Asn1.context(0, Asn1.Node.primitive(0x02, new byte[] {2})),  // version v3
                Asn1.Node.primitive(0x02, derInteger(randomSerial())),     // serial number
                raw(algId),                                                 // signature algorithm
                nameCn(issuerCn),
                Asn1.seq(utcTime(notBefore), utcTime(notAfter)),            // validity
                nameCn(subjectCn),
                raw(spki)));                                               // subjectPublicKeyInfo

        byte[] signature = sign(tbs, issuerPrivateKey);

        byte[] der = Asn1.encodeOne(Asn1.seq(
                raw(tbs),
                raw(algId),
                Asn1.bitString(signature)));
        return new Cert(der);
    }

    /** True when the key is an EC key, which signs with ECDSA and not RSA. */
    private static boolean ec(PrivateKey key) {
        return key != null && "EC".equals(key.getAlgorithm());
    }

    private static byte[] sign(byte[] tbs, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(ec(privateKey) ? "SHA256withECDSA" : "SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(tbs);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the certificate", e);
        }
    }

    // ---- DER pieces ----------------------------------------------------------

    /**
     * Wrap a complete, already-encoded DER element - an AlgorithmIdentifier or
     * an OID held as raw bytes - back into a node. These blobs already carry
     * their own tag and length, so they must not be wrapped a second time.
     */
    private static Asn1.Node raw(byte[] completeTlv) {
        return Asn1.fromTlv(completeTlv);
    }

    /** {@code Name ::= SEQUENCE OF RelativeDistinguishedName}, here a single CN. */
    private static Asn1.Node nameCn(String cn) {
        return Asn1.seq(Asn1.set(Asn1.seq(raw(OID_CN), Asn1.utf8String(cn))));
    }

    /** A UTCTime, {@code YYMMDDHHMMSSZ}. */
    private static Asn1.Node utcTime(Instant at) {
        ZonedDateTime t = at.atZone(ZoneOffset.UTC);
        String s = p2(t.getYear() % 100) + p2(t.getMonthValue()) + p2(t.getDayOfMonth())
                + p2(t.getHour()) + p2(t.getMinute()) + p2(t.getSecond()) + "Z";
        return Asn1.Node.primitive(0x17, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String p2(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    /**
     * A positive DER INTEGER: leading zero bytes are stripped, and a zero byte
     * is prepended when the top bit would otherwise make it negative.
     */
    static byte[] derInteger(byte[] b) {
        int i = 0;
        while (i < b.length - 1 && b[i] == 0x00) {
            i++;
        }
        byte[] trimmed = Arrays.copyOfRange(b, i, b.length);
        if ((trimmed[0] & 0x80) != 0) {
            byte[] out = new byte[trimmed.length + 1];
            System.arraycopy(trimmed, 0, out, 1, trimmed.length);
            return out;
        }
        return trimmed;
    }

    private static byte[] randomSerial() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        b[0] &= 0x7f; // keep the serial positive
        return b;
    }

    // ---- PEM helpers used by the control console ---------------------------

    /**
     * A PEM certificate as base64, which is how the service returns one.
     *
     * @param pem the certificate in PEM
     * @return the base64 of the PEM
     */
    public static String pemBase64(String pem) {
        return Base64.getEncoder().encodeToString(
                pem.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] fromPem(String pem) {
        if (pem == null) {
            return null;
        }
        int start = pem.indexOf("-----BEGIN CERTIFICATE-----");
        if (start < 0) {
            return null;
        }
        int bodyStart = start + "-----BEGIN CERTIFICATE-----".length();
        int end = pem.indexOf("-----END CERTIFICATE-----", bodyStart);
        if (end < 0) {
            return null;
        }
        return Base64.getMimeDecoder().decode(pem.substring(bodyStart, end));
    }

    /** Wrap DER as a PEM document. */
    public static String toPem(byte[] der) {
        return new Cert(der).pem;
    }

    /** Concatenate DER elements, for a certificate chain. */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    /** An empty, immutable list, for callers that need a chain of one. */
    public static List<byte[]> single(byte[] der) {
        return Collections.singletonList(der);
    }
}
