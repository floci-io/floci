package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TR-34 builder, against the TR-34 verifier.
 *
 * <p>The two are the mirror of each other: what the builder produces, the verifier recovers.
 * A block that one makes and the other cannot read is a block the service would reject.
 */
public class PaymentCryptographyTr34RoundTripTest {

    /** The hexadecimal the block is written in. */
    private static final HexFormat HEX = HexFormat.of();

    /** The hexadecimal the block is written in, in the capitals the service uses. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    @Test
    void buildsWhatTheVerifierRecovers() throws Exception {
        // The key receiving device: its key pair and its certificate.
        KeyPair krd = rsaKeyPair();
        // The key distribution host: the service's signing key and its certificate.
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] clear = HEX.parseHex("404142434445464748494A4B4C4D4E4F");
        String header = "B0010P0TB00E0000";
        byte[] nonce = HEX.parseHex("0102030405060708");

        byte[] block = Tr34.build(clear, header, nonce, kdh.getPrivate(), krd.getPublic());
        assertNotNull(block, "the block was not built");

        Tr34.Recovered recovered = Tr34.recoverAndVerify(HEX_UPPER.formatHex(block),
                krd.getPrivate(), kdhCertificate.pem(), null, HEX_UPPER.formatHex(nonce));

        assertEquals(HEX_UPPER.formatHex(clear), recovered.keyHex);
        assertEquals(header, recovered.header);
    }

    @Test
    void carriesTheHeader() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());

        Tr34.Recovered recovered = Tr34.recoverAndVerify(HEX_UPPER.formatHex(block),
                krd.getPrivate(), kdhCertificate.pem(), null, null);

        assertEquals("B0010P0TB00E0000", recovered.header);
    }

    @Test
    void namesBothPartiesByTheirCertificates() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted krdCertificate = CertificatesTest.mint(krd.getPublic(), "krd",
                krd.getPrivate(), "krd");
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic(),
                krdCertificate.der(), kdhCertificate.der());

        Tr34.Recovered recovered = Tr34.recoverAndVerify(HEX_UPPER.formatHex(block),
                krd.getPrivate(), kdhCertificate.pem(), null, null);

        assertEquals(HEX_UPPER.formatHex(HEX.parseHex("404142434445464748494A4B4C4D4E4F")),
                recovered.keyHex);
        // The block names its recipient by the certificate's own issuer and serial number,
        // so the receiver can tell which of its keys the block is for.
        assertEquals(HexFormat.of().formatHex(Certificates.certificateId(krdCertificate.der())),
                HexFormat.of().formatHex(recipientId(block)),
                "the recipient the block names");
    }

    @Test
    void refusesASigningCertificateThatHasExpired() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Instant past = Instant.now().minusSeconds(7200);
        Instant expired = Instant.now().minusSeconds(3600);
        Certificates.Minted expiredCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh", past, expired);

        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Tr34.recoverAndVerify(HEX_UPPER.formatHex(block), krd.getPrivate(),
                        expiredCertificate.pem(), null, null));
        assertTrue(refused.getMessage().contains("validity window"), refused.getMessage());
    }

    @Test
    void refusesASigningCertificateThatIsNotYetValid() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Instant future = Instant.now().plusSeconds(3600);
        Instant later = Instant.now().plusSeconds(7200);
        Certificates.Minted earlyCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh", future, later);

        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Tr34.recoverAndVerify(HEX_UPPER.formatHex(block), krd.getPrivate(),
                        earlyCertificate.pem(), null, null));
        assertTrue(refused.getMessage().contains("validity window"), refused.getMessage());
    }

    @Test
    void refusesABlockThatCarriesNoHeader() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        // The block is signed correctly, but its key block names no key: the header that
        // says what the block carries is empty.
        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "", null, kdh.getPrivate(), krd.getPublic());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Tr34.recoverAndVerify(HEX_UPPER.formatHex(block), krd.getPrivate(),
                        kdhCertificate.pem(), null, null));
        assertTrue(refused.getMessage().contains("no TR-31 header"), refused.getMessage());
    }

    /**
     * The header a block carries in the clear.
     *
     * <p>The header travels in the clear beside the encrypted key block, so a reader sees
     * what the block carries without opening it.
     *
     * @param block the block
     * @return the header
     */
    public static String clearHeader(byte[] block) throws IOException {
        ASN1Sequence contentInfo = ASN1Sequence.getInstance(block);
        ASN1Sequence signedData = ASN1Sequence.getInstance(
                ASN1TaggedObject.getInstance(contentInfo.getObjectAt(1)), true);
        ASN1Set signerInfos = ASN1Set.getInstance(signedData.getObjectAt(3));
        ASN1Sequence signerInfo = ASN1Sequence.getInstance(signerInfos.getObjectAt(0));
        ASN1Set attributes = ASN1Set.getInstance(
                ASN1TaggedObject.getInstance(signerInfo.getObjectAt(3)), false);
        for (ASN1Encodable element : attributes) {
            ASN1Sequence attribute = ASN1Sequence.getInstance(element);
            if (!"1.2.840.113549.1.7.1".equals(
                    ASN1ObjectIdentifier.getInstance(attribute.getObjectAt(0)).getId())) {
                continue;
            }
            ASN1Set values = ASN1Set.getInstance(attribute.getObjectAt(1));
            return new String(ASN1OctetString.getInstance(values.getObjectAt(0)).getOctets(),
                    StandardCharsets.US_ASCII);
        }
        throw new IllegalStateException("the block carries no header in the clear");
    }

    /** The recipient identifier a block carries: the receiver's certificate identifier. */
    private static byte[] recipientId(byte[] block) throws IOException {
        ASN1Sequence contentInfo = ASN1Sequence.getInstance(block);
        ASN1Sequence signedData = ASN1Sequence.getInstance(
                ASN1TaggedObject.getInstance(contentInfo.getObjectAt(1)), true);
        ASN1Sequence encap = ASN1Sequence.getInstance(signedData.getObjectAt(2));
        byte[] envelope = ASN1OctetString.getInstance(
                ASN1TaggedObject.getInstance(encap.getObjectAt(1)), true).getOctets();
        try (ASN1InputStream stream = new ASN1InputStream(envelope)) {
            stream.readObject();
            ASN1Set recipients = ASN1Set.getInstance(stream.readObject());
            ASN1Sequence recipient = ASN1Sequence.getInstance(recipients.getObjectAt(0));
            // The identifier is the issuer and serial number of the receiver's certificate.
            return ASN1Sequence.getInstance(recipient.getObjectAt(1)).getEncoded();
        }
    }

    @Test
    void anAesKeyIsEncipheredWithAes() throws Exception {
        // The key block is enciphered with the cipher the key needs, so an AES key is not
        // protected by something weaker than itself.
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] clear = HEX.parseHex("000102030405060708090A0B0C0D0E0F");
        String header = "D9999D0AB00E0000";
        byte[] nonce = HEX.parseHex("0102030405060708");

        byte[] block = Tr34.build(clear, header, nonce, kdh.getPrivate(), krd.getPublic(),
                null, kdhCertificate.der(), true);

        // The content encryption is the AES one: 2.16.840.1.101.3.4.1.2.
        assertTrue(HEX_UPPER.formatHex(block).contains("608648016503040102"),
                "the block does not name the AES content encryption");

        Tr34.Recovered recovered = Tr34.recoverAndVerify(HEX_UPPER.formatHex(block),
                krd.getPrivate(), kdhCertificate.pem(), null, HEX_UPPER.formatHex(nonce));

        assertEquals(HEX_UPPER.formatHex(clear), recovered.keyHex);
        assertEquals(header, recovered.header);
    }

    @Test
    void aKeyBlockIsReadWhateverFollowsIt() throws Exception {
        // The bytes the cipher needed follow the key block, and a block whose plaintext was
        // already a whole number of cipher blocks carries none of them, so the sequence is
        // read from the front and the bytes after it are left alone.
        ASN1EncodableVector elements = new ASN1EncodableVector();
        elements.add(new ASN1Integer(1));
        elements.add(new DERSequence(new ASN1Integer(0)));
        elements.add(new DEROctetString(new byte[16]));
        elements.add(new DERSequence(new ASN1ObjectIdentifier("1.2.840.113549.1.7.1"),
                new DERSet(new DEROctetString(
                        "B0010P0TB00E0000".getBytes(StandardCharsets.US_ASCII)))));
        byte[] keyBlock = new DERSequence(elements).getEncoded();

        Tr34.Recovered recovered = Tr34.readKeyBlock(keyBlock);

        assertEquals("B0010P0TB00E0000", recovered.header);
        assertEquals(HEX_UPPER.formatHex(new byte[16]), recovered.keyHex);
    }

    @Test
    void aOnePassBlockIsReadWithoutANonce() throws Exception {
        // A block with no nonce is a one-pass block, and it carries no nonce attribute at
        // all, which reads as an empty one.
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] clear = HEX.parseHex("404142434445464748494A4B4C4D4E4F");
        String header = "B0010P0TB00E0000";

        byte[] block = Tr34.build(clear, header, null, kdh.getPrivate(), krd.getPublic(),
                null, kdhCertificate.der());

        Tr34.Recovered recovered = Tr34.recoverAndVerify(HEX_UPPER.formatHex(block),
                krd.getPrivate(), kdhCertificate.pem(), null, null);

        assertEquals(HEX_UPPER.formatHex(clear), recovered.keyHex);
        assertEquals(header, recovered.header);
    }

    @Test
    void refusesATamperedBlock() throws Exception {
        KeyPair krd = rsaKeyPair();
        KeyPair kdh = rsaKeyPair();
        Certificates.Minted kdhCertificate = CertificatesTest.mint(kdh.getPublic(), "kdh",
                kdh.getPrivate(), "kdh");

        byte[] block = Tr34.build(HEX.parseHex("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());
        block[block.length - 1] ^= 0x01;

        try {
            Tr34.recoverAndVerify(HEX_UPPER.formatHex(block), krd.getPrivate(), kdhCertificate.pem(),
                    null, null);
            Assertions.fail("a tampered block was accepted");
        } catch (RuntimeException expected) {
            // The signature or the digest catches it.
        }
    }

    /** An RSA key pair, for the device that receives the key. */
    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
