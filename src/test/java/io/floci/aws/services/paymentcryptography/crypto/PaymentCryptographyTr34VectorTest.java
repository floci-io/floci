package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TR-34 key block codec, against the vectors the standard publishes.
 *
 * <p>The vectors are the AES samples of ASC X9 TR-34-2019 Annex B, in
 * {@code tr34-vectors/} beside this test's resources. They are the standard's own
 * byte values, not ones this branch produced, so a codec that drifts from the
 * published encoding fails here even when it still round-trips its own blocks.
 */
public class PaymentCryptographyTr34VectorTest {

    private static final HexFormat HEX = HexFormat.of();

    /** The key the standard's AES samples carry, in clear. */
    private static final String STANDARD_KEY = "0123456789ABCDEFFEDCBA9876543210";

    @Test
    void readsTheStandardAesKeyBlock() throws Exception {
        // B.2.2.2.4: the clear AES key block, with its header in the clear.
        Tr34.Recovered recovered = Tr34.readKeyBlock(resource("tr34-2019-annex-b-aes-key-block.der"));
        assertEquals(STANDARD_KEY, recovered.keyHex);
        assertEquals("D0256K0AB00E0000", recovered.header);
    }

    @Test
    void opensTheStandardAesEnvelopedDataUnderThePublishedEphemeralKey() throws Exception {
        // B.2.2.3.2: the AES key block encrypted under the published ephemeral key.
        byte[] envelope = resource("tr34-2019-annex-b-aes-enveloped-data.der");
        byte[] iv = envelopeIv(envelope);
        byte[] ciphertext = envelopeCiphertext(envelope);

        // The sample serializes the IV over eight bytes; the sixteen-byte IV that
        // produced the ciphertext is the zero-extended one, which the first CBC
        // block also gives back.
        assertArrayEquals(HEX.parseHex("0123456789ABCDEF"), iv);
        assertEquals(144, ciphertext.length);

        byte[] plain = Ciphers.aesCbcDecrypt(ciphertext, HEX.parseHex(STANDARD_KEY),
                HEX.parseHex("0123456789ABCDEF0000000000000000"));
        // PKCS#7 padding of eleven bytes, so the key block is the first 133 bytes.
        int padding = plain[plain.length - 1];
        assertEquals(11, padding);
        assertEquals(133, plain.length - padding);

        Tr34.Recovered recovered = Tr34.readKeyBlock(Bytes.pkcs7Unpad(plain, 16));
        assertEquals(STANDARD_KEY, recovered.keyHex);
        // The decrypted block carries the header the standard uses for this sample,
        // not the header of the separately published clear key block.
        assertEquals("A0256K0TB00E0000", recovered.header);
    }

    /** The IV of the sample's content encryption, as the standard wrote it. */
    private static byte[] envelopeIv(byte[] envelope) {
        return ASN1OctetString.getInstance(contentEncryption(envelope).getObjectAt(1)).getOctets();
    }

    /** The encrypted key block of the sample, under its {@code [0]} tag. */
    private static byte[] envelopeCiphertext(byte[] envelope) {
        return ASN1OctetString.getInstance(ASN1TaggedObject.getInstance(
                contentEncryption(envelope).getObjectAt(2)), false).getOctets();
    }

    /**
     * The content-encryption sequence of the sample's EnvelopedData.
     *
     * <p>The EnvelopedData is a sequence of the version, the recipients and the encrypted
     * content information, and that last one names the content encryption and carries the
     * IV and the ciphertext beside the algorithm's name. That is the layout the service's
     * own reader expects of a TR-34 envelope, and the one this sample is written in.
     */
    private static ASN1Sequence contentEncryption(byte[] envelope) {
        ASN1Sequence encryptedContentInfo = ASN1Sequence.getInstance(
                ASN1Sequence.getInstance(envelope).getObjectAt(2));
        return ASN1Sequence.getInstance(encryptedContentInfo.getObjectAt(1));
    }

    /** A vector, by the file name beside this test's resources. */
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = PaymentCryptographyTr34VectorTest.class
                .getResourceAsStream("tr34-vectors/" + name)) {
            return in.readAllBytes();
        }
    }
}
