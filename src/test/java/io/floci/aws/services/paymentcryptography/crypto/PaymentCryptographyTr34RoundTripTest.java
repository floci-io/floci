package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
