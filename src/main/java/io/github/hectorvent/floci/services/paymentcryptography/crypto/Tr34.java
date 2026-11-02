package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * TR-34 (2012, non-CMS) key block recovery and verification for {@code ImportKey}.
 *
 * <p>Mirrors the AWS Payment Cryptography reference: an outer PKCS#7 SignedData
 * carries an EnvelopedData with the symmetric key block wrapped under a TDES
 * ephemeral key, that ephemeral key RSA-OAEP-wrapped to the KRD - the service's
 * import-token key - plus a KDH signature over signed attributes that include a
 * SHA-256 digest of the envelope and the two-pass nonce.
 *
 * <p>Recovery unwraps the ephemeral key, decrypts the key block, strips PKCS#7
 * padding, and reads the clear symmetric key. Verification checks that the KDH
 * certificate chains to the registered CA, that the signature over the signed
 * attributes holds, that the embedded digest matches the envelope, and that the
 * nonce matches the supplied RandomNonce.
 */
public final class Tr34 {

    private static final String OID_DES_EDE3_CBC = "2a864886f70d0307";
    private static final String OID_SIGNED_DATA = "2a864886f70d010702";
    private static final String OID_DATA = "2a864886f70d010701";
    /** The enveloped data type, which is what the content of a TR-34 block is. */
    private static final String OID_ENVELOPED_DATA = "2a864886f70d010703";
    private static final String OID_CONTENT_TYPE = "2a864886f70d010903";
    private static final String OID_SHA256 = "608648016503040201";
    private static final String OID_SHA256_WITH_RSA = "2a864886f70d01010b";
    private static final String OID_MESSAGE_DIGEST = "2a864886f70d010904";
    private static final String OID_RANDOM_NONCE = "2a864886f70d01091903";
    private static final String OID_RSAES_OAEP = "2a864886f70d010107";
    private static final String OID_MGF1 = "2a864886f70d010108";
    private static final String OID_P_SPECIFIED = "2a864886f70d010109";

    private Tr34() {
    }

    /** The recovered clear key and the TR-31 header it carried. */
    public static final class Recovered {
        public final String keyHex;
        public final String header;

        Recovered(String keyHex, String header) {
            this.keyHex = keyHex;
            this.header = header;
        }
    }

    /** The pieces the outer SignedData yields. */
    static final class Payload {
        final byte[] envelopeBytes;
        final Asn1.Node signedAttrsNode;
        final byte[] signature;

        Payload(byte[] envelopeBytes, Asn1.Node signedAttrsNode, byte[] signature) {
            this.envelopeBytes = envelopeBytes;
            this.signedAttrsNode = signedAttrsNode;
            this.signature = signature;
        }
    }

    /** The pieces the EnvelopedData yields. */
    static final class Envelope {
        final byte[] encryptedEphemeralKey;
        final String encOid;
        final byte[] iv;
        final byte[] encryptedKeyBlock;

        Envelope(byte[] encryptedEphemeralKey, String encOid, byte[] iv, byte[] encryptedKeyBlock) {
            this.encryptedEphemeralKey = encryptedEphemeralKey;
            this.encOid = encOid;
            this.iv = iv;
            this.encryptedKeyBlock = encryptedKeyBlock;
        }
    }

    /** Parse the TR-34 payload into the pieces needed for recovery and verification. */
    public static Payload parsePayload(String payloadHex) {
        byte[] der = Hex.decode(payloadHex);
        List<Asn1.Node> top = Asn1.parse(der);
        // [ OID_signedData, [0] ]
        List<Asn1.Node> outer = top.get(0).children();
        // Children of the SignedData SEQUENCE.
        List<Asn1.Node> signedData = outer.get(1).children().get(0).children();
        // [version, digestAlgorithms SET, encapContentInfo, signerInfos SET]
        List<Asn1.Node> encapContentInfo = signedData.get(2).children();
        byte[] envelopeBytes = encapContentInfo.get(1).children().get(0).content();
        List<Asn1.Node> signerInfo = signedData.get(3).children().get(0).children();
        Asn1.Node signedAttrsNode = findChild(signerInfo, 0xa0);
        byte[] signature = signerInfo.get(signerInfo.size() - 1).content();

        return new Payload(envelopeBytes, signedAttrsNode, signature);
    }

    /** Parse the EnvelopedData: ephemeral cryptogram, content IV, and encrypted key block. */
    public static Envelope parseEnvelope(byte[] envelopeBytes) {
        // [version, SET{recipientInfo}, encryptedContentInfo]
        List<Asn1.Node> env = Asn1.parse(envelopeBytes);
        List<Asn1.Node> recipientInfo = env.get(1).children().get(0).children();
        byte[] encryptedEphemeralKey = recipientInfo.get(3).content();

        List<Asn1.Node> encContentInfo = env.get(2).children();
        List<Asn1.Node> inner = encContentInfo.get(1).children();
        String encOid = Hex.encode(inner.get(0).content());
        byte[] iv = inner.get(1).content();
        byte[] encryptedKeyBlock = findChild(inner, 0x80).content();

        return new Envelope(encryptedEphemeralKey, encOid, iv, encryptedKeyBlock);
    }

    /**
     * Build a TR-34 key block: the key encrypted under an ephemeral key, the
     * ephemeral key wrapped under the receiver's public key, and the whole
     * envelope signed with the service's signing key.
     *
     * <p>This is the mirror of {@link #recoverAndVerify}: what that recovers,
     * this produces.
     *
     * @param clearKey the key under export
     * @param tr31Header the key's TR-31 header, in the clear
     * @param nonce the random nonce the two passes agree on, or null
     * @param signingKey the service's signing key
     * @param wrappingKey the receiver's public key, which the ephemeral key is
     *     wrapped under
     * @return the TR-34 block, as the bytes of a PKCS#7 SignedData
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey,
                                PublicKey wrappingKey) {
        return build(clearKey, tr31Header, nonce, signingKey, wrappingKey, null);
    }

    /**
     * Build a TR-34 key block, naming the receiver by its certificate.
     *
     * @param receiverCertDer the receiver's certificate in DER. Its issuer and
     *     serial number name the recipient, the way the service's own sample
     *     does. When it is null, a placeholder identifier is written instead.
     * @see #build(byte[], String, byte[], PrivateKey,
     *      PublicKey)
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey,
                                PublicKey wrappingKey,
                                byte[] receiverCertDer) {
        return build(clearKey, tr31Header, nonce, signingKey, wrappingKey,
                receiverCertDer, null);
    }

    /**
     * Build a TR-34 key block, naming both parties by their certificates.
     *
     * <p>The recipient is named by the receiver's certificate, and the signer
     * by the signing certificate, both as an issuer and serial number. That is
     * how the service's own sample writes a block, so a block built here is
     * shaped like one a real HSM produces.
     *
     * @param receiverCertDer the receiver's certificate in DER. Its issuer and
     *     serial number name the recipient. When it is null, a placeholder
     *     identifier is written instead.
     * @param signerCertDer the signing certificate in DER. Its issuer and
     *     serial number name the signer. When it is null, the signer is left
     *     unnamed.
     * @see #build(byte[], String, byte[], PrivateKey,
     *      PublicKey)
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey,
                                PublicKey wrappingKey,
                                byte[] receiverCertDer,
                                byte[] signerCertDer) {
        // The key block: [version, kdhId, OCTET key, headerAttr]
        Asn1.Node keyBlock = Asn1.seq(
                Asn1.Node.primitive(0x02, new byte[] {1}),
                Asn1.seq(),
                Asn1.octetString(clearKey),
                Asn1.seq(Asn1.set(Asn1.octetString(
                        tr31Header.getBytes(StandardCharsets.US_ASCII)))));

        // An ephemeral 3DES key enciphers it, and the receiver's public key
        // wraps the ephemeral key.
        byte[] ephemeral = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(ephemeral);
        byte[] iv = new byte[8];
        random.nextBytes(iv);
        byte[] encryptedBlock = Ciphers.tdesCbcEncrypt(pkcs7(Asn1.encodeOne(keyBlock)),
                ephemeral, iv);
        byte[] encryptedEphemeral = RsaOaep.encrypt(wrappingKey, ephemeral, "SHA-256");

        // The envelope: three top-level elements, not a wrapped SEQUENCE.
        // The recipient is named by the receiver's certificate: the issuer's
        // name and the certificate's serial number.
        Asn1.Node recipientId = receiverCertDer == null
                ? Asn1.octetString(new byte[] {0x01, 0x02})
                : X509.certificateId(receiverCertDer);
        Asn1.Node recipientInfo = Asn1.seq(
                Asn1.Node.primitive(0x02, new byte[] {0}),
                recipientId,
                // The algorithm the ephemeral key was wrapped under, with the
                // RSAES-OAEP parameters: SHA-256, MGF1 with SHA-256, and an
                // empty label.
                Asn1.seq(
                        Asn1.Node.primitive(0x06, Hex.decode(OID_RSAES_OAEP)),
                        Asn1.seq(
                                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_SHA256)),
                                        Asn1.Node.primitive(0x05, new byte[0])),
                                Asn1.seq(
                                        Asn1.Node.primitive(0x06, Hex.decode(OID_MGF1)),
                                        Asn1.seq(Asn1.Node.primitive(0x06,
                                                Hex.decode(OID_SHA256)))),
                                Asn1.seq(
                                        Asn1.Node.primitive(0x06, Hex.decode(OID_P_SPECIFIED)),
                                        Asn1.octetString(new byte[0])))),
                Asn1.octetString(encryptedEphemeral));
        Asn1.Node contentEncryption = Asn1.seq(
                Asn1.Node.primitive(0x06, Hex.decode(OID_DES_EDE3_CBC)),
                Asn1.octetString(iv),
                Asn1.Node.primitive(0x80, encryptedBlock));
        Asn1.Node encryptedContentInfo = Asn1.seq(
                Asn1.Node.primitive(0x06, Hex.decode(OID_DATA)),
                contentEncryption);
        byte[] envelopeBytes = Asn1.encode(Arrays.asList(
                Asn1.Node.primitive(0x02, new byte[] {0}),
                new Asn1.Node(0x31, true, Arrays.asList(recipientInfo)),
                encryptedContentInfo));

        // The signed attributes: what the signature covers. Their order is the
        // order they are signed in, so it is part of the signature.
        Asn1.Node attributes = Asn1.seq(
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_CONTENT_TYPE)),
                        Asn1.set(Asn1.Node.primitive(0x06, Hex.decode(OID_ENVELOPED_DATA)))),
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_RANDOM_NONCE)),
                        Asn1.set(Asn1.octetString(nonce == null ? new byte[0] : nonce))),
                // The key block header, in the clear. The receiver compares it
                // with the one inside the encrypted block, so a block cannot
                // carry one header in the clear and another inside.
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_DATA)),
                        Asn1.set(Asn1.octetString(
                                tr31Header.getBytes(StandardCharsets.US_ASCII)))),
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_MESSAGE_DIGEST)),
                        Asn1.set(Asn1.octetString(Ciphers.sha256(envelopeBytes)))));

        // A PKCS#7 signature is computed over the attributes as an explicit
        // SET, which is how the verifier reads them back.
        byte[] signedAttrsDer = Asn1.encodeOne(
                new Asn1.Node(0x31, true, attributes.children()));
        byte[] signature;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(signingKey);
            signer.update(signedAttrsDer);
            signature = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the TR-34 payload could not be signed: "
                    + e.getMessage(), e);
        }

        Asn1.Node signerInfo = Asn1.seq(
                Asn1.Node.primitive(0x02, new byte[] {1}),
                // The signer is named by its certificate: issuer and serial.
                signerCertDer == null ? Asn1.seq() : X509.certificateId(signerCertDer),
                // The digest algorithm, then the signed attributes.
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_SHA256)),
                        Asn1.Node.primitive(0x05, new byte[0])),
                // The signed attributes sit directly in the [0], as CMS has
                // them: [0] IMPLICIT SET OF Attribute. The verifier re-tags
                // them as an explicit SET to get the bytes a signature is
                // computed over.
                Asn1.context(0, attributes.children().toArray(new Asn1.Node[0])),
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_SHA256_WITH_RSA)),
                        Asn1.Node.primitive(0x05, new byte[0])),
                Asn1.octetString(signature));

        Asn1.Node signedData = Asn1.seq(
                Asn1.Node.primitive(0x02, new byte[] {1}),
                Asn1.set(Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_SHA256)),
                        Asn1.Node.primitive(0x05, new byte[0]))),
                Asn1.seq(Asn1.Node.primitive(0x06, Hex.decode(OID_ENVELOPED_DATA)),
                        Asn1.context(0, Asn1.octetString(envelopeBytes))),
                Asn1.set(signerInfo));

        return Asn1.encodeOne(Asn1.seq(
                Asn1.Node.primitive(0x06, Hex.decode(OID_SIGNED_DATA)),
                Asn1.context(0, signedData)));
    }

    /** PKCS#7 padding, the counterpart of {@link #stripPkcs7}. */
    private static byte[] pkcs7(byte[] buf) {
        int pad = 8 - (buf.length % 8);
        byte[] out = Arrays.copyOf(buf, buf.length + pad);
        for (int i = buf.length; i < out.length; i++) {
            out[i] = (byte) pad;
        }
        return out;
    }

    /**
     * Recover and verify a TR-34 key block.
     *
     * @param caPublicKey the registered CA public key, or null to skip the chain check
     * @param randomNonceHex the RandomNonce supplied alongside the payload, or null to skip
     * @throws IllegalArgumentException describing the first check that failed
     */
    public static Recovered recoverAndVerify(String payloadHex, PrivateKey importPrivateKey,
                                             String signingCertPem, PublicKey caPublicKey,
                                             String randomNonceHex) {
        Payload payload = parsePayload(payloadHex);
        Envelope envelope = parseEnvelope(payload.envelopeBytes);

        if (!OID_DES_EDE3_CBC.equals(envelope.encOid)) {
            throw new IllegalArgumentException(
                    "content encryption is not 3DES-CBC (this service imports TDES key blocks only)");
        }

        // 1. The KDH signing certificate must chain to the registered CA.
        X509Certificate signingCert = parseCertificate(signingCertPem);
        if (caPublicKey != null) {
            try {
                signingCert.verify(caPublicKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("SigningKeyCertificate does not chain to the "
                        + "registered CertificateAuthorityPublicKeyIdentifier", e);
            }
        }

        // 2. The KDH signature must verify over the signed attributes, re-tagged
        //    as the explicit SET that a PKCS#7 signature is actually computed over.
        byte[] signedAttrsDer = Asn1.encodeOne(
                new Asn1.Node(0x31, true, payload.signedAttrsNode.children()));
        if (!verifySignature(signedAttrsDer, payload.signature, signingCert.getPublicKey())) {
            throw new IllegalArgumentException("TR-34 authentication-data signature did not verify "
                    + "under the KDH certificate");
        }

        // 3. The signed message digest must equal SHA-256 over the envelope.
        byte[] digestAttr = attrValue(payload.signedAttrsNode, OID_MESSAGE_DIGEST);
        byte[] envDigest = Ciphers.sha256(payload.envelopeBytes);
        if (digestAttr == null || !MessageDigest.isEqual(digestAttr, envDigest)) {
            throw new IllegalArgumentException("signed message-digest does not match the envelope "
                    + "(tamper or wrong content)");
        }

        // 5. The two-pass nonce must match the supplied RandomNonce.
        if (randomNonceHex != null) {
            byte[] nonceAttr = attrValue(payload.signedAttrsNode, OID_RANDOM_NONCE);
            if (nonceAttr == null
                    || !Hex.encodeUpper(nonceAttr).equalsIgnoreCase(randomNonceHex)) {
                throw new IllegalArgumentException(
                        "RandomNonce does not match the nonce in the signed payload");
            }
        }

        // 5. Recover the ephemeral key and decrypt the key block. The OAEP
        //    digest is SHA-256, and MGF1 follows it, which is what the
        //    JavaScript side produced.
        byte[] ephemeralKey;
        try {
            ephemeralKey = RsaOaep.decrypt(importPrivateKey, envelope.encryptedEphemeralKey, "SHA-256");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ephemeral key did not unwrap under the import-token "
                    + "key (wrong token)", e);
        }
        byte[] clearBlock = stripPkcs7(Ciphers.tdesCbcDecrypt(
                envelope.encryptedKeyBlock, ephemeralKey, envelope.iv));

        Tr34.Recovered recovered = readKeyBlock(clearBlock);

        // 4. The key block header in the clear must be the one inside the
        //    encrypted block, so a block cannot carry one header in the clear
        //    and another inside. The header travels in the clear as a signed
        //    attribute; a block that does not carry one is accepted, because
        //    there is nothing to compare it with.
        byte[] clearHeader = attrValue(payload.signedAttrsNode, OID_DATA);
        if (clearHeader != null && clearHeader.length > 0) {
            String inClear = new String(clearHeader, StandardCharsets.US_ASCII);
            if (!inClear.equals(recovered.header)) {
                throw new IllegalArgumentException("the key block header in the clear is '"
                        + inClear + "' but the one inside the encrypted block is '"
                        + recovered.header + "'");
            }
        }

        return recovered;
    }

    /** The decrypted key block: the clear symmetric key and its TR-31 header. */
    private static Recovered readKeyBlock(byte[] clearBlock) {
        // [version, kdhId, OCTET key, headerAttr]
        List<Asn1.Node> kb = Asn1.parse(clearBlock).get(0).children();
        Asn1.Node keyNode = findChild(kb, 0x04);
        if (keyNode == null) {
            throw new IllegalArgumentException(
                    "symmetric key octet string not found in key block");
        }
        String header = "";
        Asn1.Node headerAttr = findHeaderAttr(kb);
        if (headerAttr != null) {
            Asn1.Node set = findChild(headerAttr.children(), 0x31);
            if (set != null && !set.children().isEmpty()) {
                header = new String(set.children().get(0).content(), StandardCharsets.US_ASCII);
            }
        }
        return new Recovered(Hex.encodeUpper(keyNode.content()), header);
    }

    /** The header attribute is the SEQUENCE that contains at least one SET. */
    private static Asn1.Node findHeaderAttr(List<Asn1.Node> nodes) {
        for (Asn1.Node n : nodes) {
            if (n.tag != 0x30 || !n.constructed) {
                continue;
            }
            for (Asn1.Node child : n.children()) {
                if (child.tag == 0x31) {
                    return n;
                }
            }
        }
        return null;
    }

    /** Strip PKCS#7 padding, rejecting malformed padding rather than truncating. */
    static byte[] stripPkcs7(byte[] buf) {
        int pad = buf[buf.length - 1] & 0xff;
        if (pad < 1 || pad > buf.length) {
            throw new IllegalArgumentException("invalid PKCS#7 padding");
        }
        for (int i = buf.length - pad; i < buf.length; i++) {
            if ((buf[i] & 0xff) != pad) {
                throw new IllegalArgumentException("invalid PKCS#7 padding");
            }
        }
        return Arrays.copyOfRange(buf, 0, buf.length - pad);
    }

    private static X509Certificate parseCertificate(String pem) {
        // The service sends the certificate base64 encoded, and a caller may
        // send the PEM itself; both are accepted.
        try {
            return X509.parseCertificate(pem);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "SigningKeyCertificate is not a valid certificate: " + e.getMessage(), e);
        }
    }

    private static boolean verifySignature(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The value bytes of a signed attribute, located by its OID. */
    static byte[] attrValue(Asn1.Node signedAttrs, String oidHex) {
        for (Asn1.Node attr : signedAttrs.children()) {
            Asn1.Node oid = findChild(attr.children(), 0x06);
            if (oid != null && Hex.encode(oid.content()).equals(oidHex)) {
                Asn1.Node valSet = findChild(attr.children(), 0x31);
                return valSet == null || valSet.children().isEmpty()
                        ? null : valSet.children().get(0).content();
            }
        }
        return null;
    }

    /** The first child with the given tag, or null. */
    static Asn1.Node findChild(List<Asn1.Node> nodes, int tag) {
        for (Asn1.Node n : nodes) {
            if (n.tag == tag) {
                return n;
            }
        }
        return null;
    }

    /** Recover with the import key given as a generic {@link Key}. */
    public static Recovered recoverAndVerify(String payloadHex, Key importPrivateKey,
                                             String signingCertPem, PublicKey caPublicKey,
                                             String randomNonceHex) {
        return recoverAndVerify(payloadHex, (PrivateKey) importPrivateKey, signingCertPem,
                caPublicKey, randomNonceHex);
    }
}
