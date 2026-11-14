package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TR-34 (2012) key blocks: the form a key leaves the service in when it is wrapped for a
 * receiving device, and the form it comes back in.
 *
 * <p>A TR-34 block is a PKCS#7 SignedData whose content is an EnvelopedData:
 *
 * <ul>
 *   <li>the key block is encrypted under an ephemeral key, whose cipher follows the key it
 *       protects;
 *   <li>that ephemeral key is wrapped under the receiving device's public key with RSA-OAEP;
 *   <li>the envelope is signed by the service's signing key, over the signed attributes that
 *       carry the content type, the two-pass nonce, the TR-31 header and the envelope digest.
 * </ul>
 *
 * <p>The shape is the one the service's own reference sample writes and the one the service
 * reads: an {@code X9_TR34_2012} key block.
 *
 * <p>The ASN.1 is BouncyCastle's and the cryptography is the JDK's; no tag and no length is
 * written by hand here.
 */
public final class Tr34 {

    /** The signed data content type: 1.2.840.113549.1.7.2. */
    private static final ASN1ObjectIdentifier OID_SIGNED_DATA =
            new ASN1ObjectIdentifier("1.2.840.113549.1.7.2");

    /** The enveloped data content type: 1.2.840.113549.1.7.3. */
    private static final ASN1ObjectIdentifier OID_ENVELOPED_DATA =
            new ASN1ObjectIdentifier("1.2.840.113549.1.7.3");

    /** The data content type: 1.2.840.113549.1.7.1. */
    private static final ASN1ObjectIdentifier OID_DATA =
            new ASN1ObjectIdentifier("1.2.840.113549.1.7.1");

    /** The content type attribute: 1.2.840.113549.1.9.3. */
    private static final ASN1ObjectIdentifier OID_CONTENT_TYPE =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.3");

    /** The message digest attribute: 1.2.840.113549.1.9.4. */
    private static final ASN1ObjectIdentifier OID_MESSAGE_DIGEST =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.4");

    /** The two-pass nonce attribute: 1.2.840.113549.1.9.25.3. */
    private static final ASN1ObjectIdentifier OID_RANDOM_NONCE =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.25.3");

    /** The RSA encryption algorithm: 1.2.840.113549.1.1.1. */
    private static final ASN1ObjectIdentifier OID_RSA_ENCRYPTION =
            new ASN1ObjectIdentifier("1.2.840.113549.1.1.1");

    /** The RSAES-OAEP key transport algorithm: 1.2.840.113549.1.1.7. */
    private static final ASN1ObjectIdentifier OID_RSAES_OAEP =
            new ASN1ObjectIdentifier("1.2.840.113549.1.1.7");

    /** The MGF1 key transport algorithm: 1.2.840.113549.1.1.8. */
    private static final ASN1ObjectIdentifier OID_MGF1 =
            new ASN1ObjectIdentifier("1.2.840.113549.1.1.8");

    /** The P source algorithm: 1.2.840.113549.1.1.9. */
    private static final ASN1ObjectIdentifier OID_P_SPECIFIED =
            new ASN1ObjectIdentifier("1.2.840.113549.1.1.9");

    /** The SHA-256 digest algorithm: 2.16.840.1.101.3.4.2.1. */
    private static final ASN1ObjectIdentifier OID_SHA256 =
            new ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1");

    /** The Triple DES in CBC mode content encryption: 1.2.840.113549.3.7. */
    private static final ASN1ObjectIdentifier OID_DES_EDE3_CBC =
            new ASN1ObjectIdentifier("1.2.840.113549.3.7");

    /** The AES-128 in CBC mode content encryption: 2.16.840.1.101.3.4.1.2. */
    private static final ASN1ObjectIdentifier OID_AES_128_CBC =
            new ASN1ObjectIdentifier("2.16.840.1.101.3.4.1.2");

    /**
     * The order the signed attributes are written in.
     *
     * <p>A signed attribute set has no order of its own, but the bytes a signature covers do.
     * The reference writes the attributes in this order, so that is the order this writes
     * them in, and the order a block that arrives is read back in.
     */
    private static final List<ASN1ObjectIdentifier> ATTRIBUTE_ORDER = List.of(
            OID_CONTENT_TYPE, OID_RANDOM_NONCE, OID_DATA, OID_MESSAGE_DIGEST);

    /** The hexadecimal the block is written in. */
    private static final HexFormat HEX = HexFormat.of();

    /** The hexadecimal the block is written in, in the capitals the reference uses. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    /** The block size of the Triple DES cipher the key block may be encrypted under. */
    private static final int TDES_BLOCK_SIZE = 8;

    /** The block size of the AES cipher the key block may be encrypted under. */
    private static final int AES_BLOCK_SIZE = 16;

    /** The size of the ephemeral Triple DES key. */
    private static final int EPHEMERAL_TDES_KEY_SIZE = 24;

    /** The size of the ephemeral AES-128 key. */
    private static final int EPHEMERAL_AES_KEY_SIZE = 16;

    /** The length of the TR-31 header a block carries, in characters. */
    private static final int HEADER_LENGTH = 16;

    private Tr34() {
    }

    /** The recovered clear key and the TR-31 header it carried. */
    public static final class Recovered {

        /** The clear key, in hexadecimal. */
        public final String keyHex;

        /** The TR-31 header the block carried, in the clear. */
        public final String header;

        Recovered(String keyHex, String header) {
            this.keyHex = keyHex;
            this.header = header;
        }
    }

    /**
     * Build a TR-34 key block: the key encrypted under an ephemeral key, the ephemeral key
     * wrapped under the receiver's public key, and the whole envelope signed with the
     * service's signing key.
     *
     * <p>This is the mirror of {@link #recoverAndVerify}: what that recovers, this produces.
     *
     * @param clearKey the key under export
     * @param tr31Header the key's TR-31 header, in the clear
     * @param nonce the random nonce the two passes agree on, or null
     * @param signingKey the service's signing key
     * @param wrappingKey the receiver's public key, which the ephemeral key is wrapped under
     * @return the TR-34 block, as the bytes of a PKCS#7 SignedData
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey, PublicKey wrappingKey) {
        return build(clearKey, tr31Header, nonce, signingKey, wrappingKey, null, null);
    }

    /**
     * Build a TR-34 key block, naming the receiver by its certificate.
     *
     * @param receiverCertDer the receiver's certificate in DER. Its issuer and serial number
     *     name the recipient, the way the service's own sample does.
     * @see #build(byte[], String, byte[], PrivateKey, PublicKey)
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey, PublicKey wrappingKey,
                                byte[] receiverCertDer) {
        return build(clearKey, tr31Header, nonce, signingKey, wrappingKey, receiverCertDer, null);
    }

    /**
     * Build a TR-34 key block, naming both parties by their certificates.
     *
     * <p>The recipient is named by the receiver's certificate, and the signer by the signing
     * certificate, both as an issuer and a serial number. That is how the service's own sample
     * writes a block, so a block built here is shaped like one a real host produces.
     *
     * @param receiverCertDer the receiver's certificate in DER, or null to leave the recipient
     *     unnamed
     * @param signerCertDer the signing certificate in DER, or null to leave the signer unnamed
     * @see #build(byte[], String, byte[], PrivateKey, PublicKey)
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey, PublicKey wrappingKey,
                                byte[] receiverCertDer, byte[] signerCertDer) {
        return build(clearKey, tr31Header, nonce, signingKey, wrappingKey, receiverCertDer,
                signerCertDer, false);
    }

    /**
     * Build a TR-34 key block, whose key block is enciphered with the key's own cipher.
     *
     * <p>The content encryption follows the key the block carries, as the reference does: a
     * Triple DES key is enciphered with Triple DES and an AES key with AES-128, so that the
     * key is never protected by a cipher weaker than itself.
     *
     * @param clearKey the key the block carries
     * @param tr31Header the TR-31 header that names the key
     * @param nonce the two-pass nonce, or null for a one-pass block, which carries no nonce
     *     attribute
     * @param signingKey the signing key of the host
     * @param wrappingKey the receiver's public key
     * @param receiverCertDer the receiver's certificate in DER, or null to leave the recipient
     *     unnamed
     * @param signerCertDer the signing certificate in DER, or null to leave the signer unnamed
     * @param aes whether the key the block carries is an AES one
     * @return the block
     */
    public static byte[] build(byte[] clearKey, String tr31Header, byte[] nonce,
                                PrivateKey signingKey, PublicKey wrappingKey,
                                byte[] receiverCertDer, byte[] signerCertDer, boolean aes) {
        byte[] header = tr31Header.getBytes(StandardCharsets.US_ASCII);

        // The key block: [version, the host's identifier, the key, the header attribute].
        ASN1EncodableVector keyBlock = new ASN1EncodableVector();
        keyBlock.add(new ASN1Integer(1));
        keyBlock.add(certificateId(signerCertDer));
        keyBlock.add(new DEROctetString(clearKey));
        keyBlock.add(attribute(OID_DATA, new DEROctetString(header)));

        // An ephemeral key enciphers the block, and the receiver's public key wraps the
        // ephemeral key. The cipher is the one the key the block carries needs, so that the
        // key is not protected by something weaker than itself.
        SecureRandom random = new SecureRandom();
        byte[] ephemeral = new byte[aes ? EPHEMERAL_AES_KEY_SIZE : EPHEMERAL_TDES_KEY_SIZE];
        random.nextBytes(ephemeral);
        byte[] iv = new byte[aes ? AES_BLOCK_SIZE : TDES_BLOCK_SIZE];
        random.nextBytes(iv);
        byte[] padded = pad(encoded(new DERSequence(keyBlock)), aes ? AES_BLOCK_SIZE : TDES_BLOCK_SIZE);
        byte[] encryptedBlock = aes
                ? Ciphers.aesCbcEncrypt(padded, ephemeral, iv)
                : Ciphers.tdesCbcEncrypt(padded, Ciphers.widenTdesKey(ephemeral), iv);
        byte[] encryptedEphemeral = RsaOaep.encrypt(wrappingKey, ephemeral, "SHA-256");

        // The recipient information: the receiver's certificate identifier, the algorithm the
        // ephemeral key was wrapped under, and the wrapped key itself.
        ASN1EncodableVector recipientInfo = new ASN1EncodableVector();
        recipientInfo.add(new ASN1Integer(0));
        recipientInfo.add(certificateId(receiverCertDer));
        recipientInfo.add(keyTransport());
        recipientInfo.add(new DEROctetString(encryptedEphemeral));

        // The encrypted content: the algorithm, the vector it was encrypted under, and the
        // encrypted block.
        ASN1EncodableVector contentEncryption = new ASN1EncodableVector();
        contentEncryption.add(aes ? OID_AES_128_CBC : OID_DES_EDE3_CBC);
        contentEncryption.add(new DEROctetString(iv));
        contentEncryption.add(new DERTaggedObject(false, 0, new DEROctetString(encryptedBlock)));

        ASN1EncodableVector encryptedContentInfo = new ASN1EncodableVector();
        encryptedContentInfo.add(OID_DATA);
        encryptedContentInfo.add(new DERSequence(contentEncryption));

        // The envelope is three elements, not a SEQUENCE: the version, the recipients and the
        // encrypted content. That is the shape the reference writes and the shape the reader
        // takes.
        byte[] envelope = concat(encoded(new ASN1Integer(0)),
                encoded(new DERSet(new DERSequence(recipientInfo))),
                encoded(new DERSequence(encryptedContentInfo)));

        // The signed attributes: what the signature covers. The header travels in the clear,
        // so a block cannot carry one header outside and another inside.
        ASN1EncodableVector attributes = new ASN1EncodableVector();
        attributes.add(attribute(OID_CONTENT_TYPE, OID_ENVELOPED_DATA));
        // A block with a nonce is a two-pass block, and one without is a one-pass block,
        // which carries no nonce attribute at all.
        if (nonce != null) {
            attributes.add(attribute(OID_RANDOM_NONCE, new DEROctetString(nonce)));
        }
        attributes.add(attribute(OID_DATA, new DEROctetString(header)));
        attributes.add(attribute(OID_MESSAGE_DIGEST, new DEROctetString(digest(envelope))));

        byte[] signature = sign(signingKey, signedAttributes(attributes));

        // The signer: the host's identifier, the digest algorithm, the attributes, the
        // signature algorithm and the signature.
        ASN1EncodableVector signerInfo = new ASN1EncodableVector();
        signerInfo.add(new ASN1Integer(1));
        signerInfo.add(certificateId(signerCertDer));
        signerInfo.add(new AlgorithmIdentifier(OID_SHA256));
        signerInfo.add(new DERTaggedObject(false, 0, new DERSequence(attributes)));
        signerInfo.add(new AlgorithmIdentifier(OID_RSA_ENCRYPTION, DERNull.INSTANCE));
        signerInfo.add(new DEROctetString(signature));

        ASN1EncodableVector digestAlgorithms = new ASN1EncodableVector();
        digestAlgorithms.add(new AlgorithmIdentifier(OID_SHA256));

        ASN1EncodableVector contentInfo = new ASN1EncodableVector();
        contentInfo.add(OID_ENVELOPED_DATA);
        contentInfo.add(new DERTaggedObject(true, 0, new DEROctetString(envelope)));

        ASN1EncodableVector signerInfos = new ASN1EncodableVector();
        signerInfos.add(new DERSequence(signerInfo));

        ASN1EncodableVector signedData = new ASN1EncodableVector();
        signedData.add(new ASN1Integer(1));
        signedData.add(new DERSet(digestAlgorithms));
        signedData.add(new DERSequence(contentInfo));
        signedData.add(new DERSet(signerInfos));

        ASN1EncodableVector payload = new ASN1EncodableVector();
        payload.add(OID_SIGNED_DATA);
        payload.add(new DERTaggedObject(true, 0, new DERSequence(signedData)));
        return encoded(new DERSequence(payload));
    }

    /**
     * Recover and verify a TR-34 key block.
     *
     * <p>The checks are, in order: the signing certificate has to chain to the registered
     * authority, the signature over the signed attributes has to verify, the digest the
     * attributes carry has to be the digest of the envelope, the nonce has to be the one the
     * request quotes, the ephemeral key has to unwrap under the import key, and the header in
     * the clear has to be the one inside the block.
     *
     * @param payloadHex the block, in hexadecimal
     * @param importPrivateKey the private key the import token holds
     * @param signingCertPem the certificate of the host that signed the block
     * @param caPublicKey the registered authority's public key, or null to skip the chain check
     * @param randomNonceHex the nonce supplied alongside the payload, or null to skip that check
     * @return the clear key and the header it carried
     * @throws IllegalArgumentException describing the first check that failed
     */
    public static Recovered recoverAndVerify(String payloadHex, PrivateKey importPrivateKey,
                                             String signingCertPem, PublicKey caPublicKey,
                                             String randomNonceHex) {
        byte[] der = HEX.parseHex(payloadHex.trim());
        ASN1Sequence contentInfo = ASN1Sequence.getInstance(der);
        ASN1ObjectIdentifier contentType = ASN1ObjectIdentifier.getInstance(contentInfo.getObjectAt(0));
        if (!OID_SIGNED_DATA.equals(contentType)) {
            throw new IllegalArgumentException("the payload is not a signed TR-34 block");
        }
        ASN1Sequence signedData = ASN1Sequence.getInstance(
                ASN1TaggedObject.getInstance(contentInfo.getObjectAt(1)), true);

        // [version, digest algorithms, content info, signer infos]
        ASN1Sequence encap = ASN1Sequence.getInstance(signedData.getObjectAt(2));
        ASN1ObjectIdentifier encapType = ASN1ObjectIdentifier.getInstance(encap.getObjectAt(0));
        if (!OID_ENVELOPED_DATA.equals(encapType)) {
            throw new IllegalArgumentException("the TR-34 block does not carry an enveloped key");
        }
        byte[] envelope = ASN1OctetString.getInstance(
                ASN1TaggedObject.getInstance(encap.getObjectAt(1)), true).getOctets();

        ASN1Set signerInfos = ASN1Set.getInstance(signedData.getObjectAt(3));
        ASN1Sequence signerInfo = ASN1Sequence.getInstance(signerInfos.getObjectAt(0));
        // [version, signer id, digest algorithm, signed attributes, signature algorithm,
        //  signature]
        ASN1Set signedAttributes = ASN1Set.getInstance(
                ASN1TaggedObject.getInstance(signerInfo.getObjectAt(3)), false);
        byte[] signature = ASN1OctetString.getInstance(signerInfo.getObjectAt(5)).getOctets();

        // The envelope is three elements, not a SEQUENCE.
        ASN1Integer envelopeVersion;
        ASN1Set recipientInfos;
        ASN1Sequence encryptedContentInfo;
        try (ASN1InputStream stream = new ASN1InputStream(envelope)) {
            envelopeVersion = ASN1Integer.getInstance(stream.readObject());
            recipientInfos = ASN1Set.getInstance(stream.readObject());
            encryptedContentInfo = ASN1Sequence.getInstance(stream.readObject());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the TR-34 envelope does not parse: "
                    + e.getMessage(), e);
        }
        if (envelopeVersion.intValueExact() != 0) {
            throw new IllegalArgumentException("the TR-34 envelope is of version "
                    + envelopeVersion.intValueExact() + ", which this service does not read");
        }

        ASN1Sequence recipientInfo = ASN1Sequence.getInstance(recipientInfos.getObjectAt(0));
        byte[] encryptedEphemeral = ASN1OctetString.getInstance(recipientInfo.getObjectAt(3))
                .getOctets();

        ASN1Sequence contentEncryption = ASN1Sequence.getInstance(encryptedContentInfo.getObjectAt(1));
        ASN1ObjectIdentifier encryption = ASN1ObjectIdentifier.getInstance(
                contentEncryption.getObjectAt(0));
        boolean aes = OID_AES_128_CBC.equals(encryption);
        if (!aes && !OID_DES_EDE3_CBC.equals(encryption)) {
            throw new IllegalArgumentException("content encryption is neither 3DES-CBC nor "
                    + "AES-128-CBC (this service imports the two the reference writes)");
        }
        byte[] iv = ASN1OctetString.getInstance(contentEncryption.getObjectAt(1)).getOctets();
        byte[] encryptedBlock = ASN1OctetString.getInstance(
                ASN1TaggedObject.getInstance(contentEncryption.getObjectAt(2)), false).getOctets();

        // The signing certificate has to chain to the registered authority, and it has to be
        // inside its validity window: a certificate that has expired, or that is not valid
        // yet, is not a certificate the authority vouches for now, so its key does not
        // authenticate a block.
        X509Certificate signingCertificate = Certificates.parseCertificate(signingCertPem);
        try {
            signingCertificate.checkValidity();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the signing certificate is outside its validity "
                    + "window: " + e.getMessage());
        }
        if (caPublicKey != null) {
            try {
                signingCertificate.verify(caPublicKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("SigningKeyCertificate does not chain to the "
                        + "registered CertificateAuthorityPublicKeyIdentifier", e);
            }
        }

        // The signature has to verify over the signed attributes.
        if (!verifies(signedAttributes, signature, signingCertificate.getPublicKey())) {
            throw new IllegalArgumentException("TR-34 authentication-data signature did not verify "
                    + "under the KDH certificate");
        }

        // The digest the attributes carry has to be the digest of the envelope.
        byte[] digestAttribute = attributeValue(signedAttributes, OID_MESSAGE_DIGEST);
        if (digestAttribute == null || !MessageDigest.isEqual(digestAttribute, digest(envelope))) {
            throw new IllegalArgumentException("signed message-digest does not match the envelope "
                    + "(tamper or wrong content)");
        }

        // The two-pass nonce has to be the one the request quotes. A request that quotes
        // none expects a block that carries none, so a block that carries one may not come
        // in under a request that names no nonce. A block that carries no nonce attribute
        // is a one-pass block, and it carries an empty one.
        String quoted = randomNonceHex == null ? "" : randomNonceHex;
        byte[] nonceAttribute = attributeValue(signedAttributes, OID_RANDOM_NONCE);
        String carried = nonceAttribute == null ? "" : HEX_UPPER.formatHex(nonceAttribute);
        if (!carried.equalsIgnoreCase(quoted)) {
            throw new IllegalArgumentException(
                    "RandomNonce does not match the nonce in the signed payload");
        }

        // Recover the ephemeral key and read the block.
        byte[] ephemeral;
        try {
            ephemeral = RsaOaep.decrypt(importPrivateKey, encryptedEphemeral, "SHA-256");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("ephemeral key did not unwrap under the import-token "
                    + "key (wrong token)", e);
        }
        byte[] clearBlock = aes
                ? Ciphers.aesCbcDecrypt(encryptedBlock, ephemeral, iv)
                : Ciphers.tdesCbcDecrypt(encryptedBlock, Ciphers.widenTdesKey(ephemeral), iv);

        Recovered recovered = readKeyBlock(clearBlock);

        // The header in the clear has to be the one inside the encrypted block, so a block
        // cannot carry one header outside and another inside.
        byte[] clearHeader = attributeValue(signedAttributes, OID_DATA);
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

    /**
     * The bytes a PKCS#7 signature covers: the signed attributes as a DER set.
     *
     * <p>The attributes are a set, and a set's encoding sorts its elements, but the
     * signature was made over the bytes the signer wrote, which are the attributes in the
     * order it wrote them. BouncyCastle's set sorts and its BER set has no definite length,
     * so the encoding is built as the sequence of the attributes and re-tagged as the set
     * it is: the two differ in that one tag byte.
     *
     * @param attributes the attributes, in the order they are written in
     * @return the bytes a signature over the attributes is made over
     */
    private static byte[] signedAttributes(ASN1EncodableVector attributes) {
        byte[] encoded = encoded(new DERSequence(attributes));
        // The set's tag, where the sequence's tag was.
        encoded[0] = 0x31;
        return encoded;
    }

    /**
     * Whether the signature verifies over the signed attributes.
     *
     * <p>The attributes are a set, and a set has no order, but the bytes a signature covers
     * do. BouncyCastle sorts a set when it reads one, so the order the signer used is gone
     * by the time the attributes are parsed. The signature is therefore tried over both
     * orders the set can be written in: the one the reference writes, and the sorted one a
     * strictly DER encoder would write. Both carry the same attributes, so a block that
     * verifies under either is authentic.
     */
    private static boolean verifies(ASN1Set signedAttributes, byte[] signature, PublicKey key) {
        for (ASN1EncodableVector ordering : orderings(signedAttributes)) {
            if (verifySignature(signedAttributes(ordering), signature, key)) {
                return true;
            }
        }
        return false;
    }

    /** The orders the signed attributes can be written in. */
    private static List<ASN1EncodableVector> orderings(ASN1Set signedAttributes) {
        Map<ASN1ObjectIdentifier, ASN1Encodable> byOid = new LinkedHashMap<>();
        for (int i = 0; i < signedAttributes.size(); i++) {
            ASN1Sequence attribute = ASN1Sequence.getInstance(signedAttributes.getObjectAt(i));
            byOid.put(attributeOid(attribute), attribute);
        }

        ASN1EncodableVector canonical = new ASN1EncodableVector();
        for (ASN1ObjectIdentifier oid : ATTRIBUTE_ORDER) {
            ASN1Encodable attribute = byOid.remove(oid);
            if (attribute != null) {
                canonical.add(attribute);
            }
        }
        for (ASN1Encodable attribute : byOid.values()) {
            canonical.add(attribute);
        }
        ASN1EncodableVector sorted = new ASN1EncodableVector();
        for (int i = 0; i < signedAttributes.size(); i++) {
            sorted.add(signedAttributes.getObjectAt(i));
        }
        return List.of(canonical, sorted);
    }

    /** Whether a signature verifies over the given bytes. */
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

    /** The signature over the given bytes, under the given key. */
    private static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the TR-34 payload could not be signed: "
                    + e.getMessage(), e);
        }
    }

    /** The identifier of the party a certificate belongs to, as an issuer and a serial number. */
    private static ASN1Encodable certificateId(byte[] certificateDer) {
        if (certificateDer == null) {
            // No certificate to name: the identifier is left empty.
            return new DERSequence();
        }
        return decoded(Certificates.certificateId(certificateDer));
    }

    /** The algorithm the ephemeral key is wrapped under: RSAES-OAEP over SHA-256. */
    private static ASN1Encodable keyTransport() {
        ASN1EncodableVector hash = new ASN1EncodableVector();
        hash.add(OID_SHA256);
        hash.add(DERNull.INSTANCE);

        ASN1EncodableVector maskGenHash = new ASN1EncodableVector();
        maskGenHash.add(OID_SHA256);

        ASN1EncodableVector maskGen = new ASN1EncodableVector();
        maskGen.add(OID_MGF1);
        maskGen.add(new DERSequence(maskGenHash));

        ASN1EncodableVector pSource = new ASN1EncodableVector();
        pSource.add(OID_P_SPECIFIED);
        pSource.add(new DEROctetString(new byte[0]));

        ASN1EncodableVector parameters = new ASN1EncodableVector();
        parameters.add(new DERSequence(hash));
        parameters.add(new DERSequence(maskGen));
        parameters.add(new DERSequence(pSource));
        return new AlgorithmIdentifier(OID_RSAES_OAEP, new DERSequence(parameters));
    }

    /** One signed attribute: its type and the set of values it carries. */
    private static ASN1Sequence attribute(ASN1ObjectIdentifier oid, ASN1Encodable value) {
        ASN1EncodableVector values = new ASN1EncodableVector();
        values.add(value);
        ASN1EncodableVector attribute = new ASN1EncodableVector();
        attribute.add(oid);
        attribute.add(new DERSet(values));
        return new DERSequence(attribute);
    }

    /** The type of a signed attribute. */
    private static ASN1ObjectIdentifier attributeOid(ASN1Sequence attribute) {
        return ASN1ObjectIdentifier.getInstance(attribute.getObjectAt(0));
    }

    /** The value a signed attribute carries, or null when it carries none. */
    private static byte[] attributeValue(ASN1Set attributes, ASN1ObjectIdentifier oid) {
        ASN1Sequence attribute = null;
        for (int i = 0; i < attributes.size(); i++) {
            ASN1Sequence candidate = ASN1Sequence.getInstance(attributes.getObjectAt(i));
            if (oid.equals(attributeOid(candidate))) {
                attribute = candidate;
                break;
            }
        }
        if (attribute == null) {
            return null;
        }
        ASN1Set values = ASN1Set.getInstance(attribute.getObjectAt(1));
        if (values.size() == 0) {
            return null;
        }
        ASN1Encodable value = values.getObjectAt(0);
        if (value instanceof ASN1OctetString octets) {
            return octets.getOctets();
        }
        return encoded(value.toASN1Primitive());
    }

    /** The decrypted key block: the clear symmetric key and its TR-31 header. */
    static Recovered readKeyBlock(byte[] clearBlock) {
        // The key block is a sequence, and the bytes the cipher needed follow it. A block
        // whose plaintext was already a whole number of cipher blocks carries none of them,
        // so the sequence is read from the front and the bytes after it are left alone.
        // The signature covers the digest of the envelope, so they are authenticated either
        // way.
        ASN1Sequence keyBlock;
        try (ASN1InputStream stream = new ASN1InputStream(clearBlock)) {
            keyBlock = ASN1Sequence.getInstance(stream.readObject());
        } catch (IOException e) {
            throw new IllegalArgumentException("the key block does not parse: " + e.getMessage(), e);
        }
        byte[] key = null;
        String header = "";
        for (int i = 0; i < keyBlock.size(); i++) {
            ASN1Encodable element = keyBlock.getObjectAt(i);
            if (element instanceof ASN1OctetString octets) {
                key = octets.getOctets();
                continue;
            }
            // The header attribute is the sequence that carries the data type and the header.
            if (element instanceof ASN1Sequence sequence && sequence.size() == 2
                    && sequence.getObjectAt(0) instanceof ASN1ObjectIdentifier) {
                ASN1Set values = ASN1Set.getInstance(sequence.getObjectAt(1));
                if (values.size() > 0) {
                    header = new String(
                            ASN1OctetString.getInstance(values.getObjectAt(0)).getOctets(),
                            StandardCharsets.US_ASCII);
                }
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("symmetric key octet string not found in key block");
        }
        if (header.length() < HEADER_LENGTH) {
            // The header names the key the block carries, so a block without one says
            // nothing about what it carries. It is refused here rather than read, because
            // the readers of a header index into it and an empty one has nothing to index.
            throw new IllegalArgumentException("the key block carries no TR-31 header, or one of "
                    + "fewer than " + HEADER_LENGTH + " characters");
        }
        return new Recovered(HEX_UPPER.formatHex(key), header);
    }

    /** The PKCS#7 padding of a block. */
    private static byte[] pad(byte[] buffer, int blockSize) {
        int pad = blockSize - (buffer.length % blockSize);
        byte[] out = Arrays.copyOf(buffer, buffer.length + pad);
        for (int i = buffer.length; i < out.length; i++) {
            out[i] = (byte) pad;
        }
        return out;
    }

    /** The SHA-256 digest of the given bytes. */
    private static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The DER encoding of a value.
     *
     * <p>BouncyCastle declares the checked exception on the encoding, but an encoding that
     * fails here would be a fault in this class rather than in anything the caller sent.
     *
     * @param value the value to encode
     * @return the encoded bytes
     */
    private static byte[] encoded(ASN1Encodable value) {
        try {
            return value.toASN1Primitive().getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException("the TR-34 structure could not be encoded: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The value the given bytes hold.
     *
     * @param der the encoded bytes
     * @return the parsed value
     */
    private static ASN1Encodable decoded(byte[] der) {
        try {
            return ASN1Primitive.fromByteArray(der);
        } catch (IOException e) {
            throw new IllegalStateException("the TR-34 structure could not be parsed: "
                    + e.getMessage(), e);
        }
    }

    /** The given byte arrays, one after the other. */
    private static byte[] concat(byte[]... parts) {        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
