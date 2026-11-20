package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TR-34 key block codec, against the vectors the standard publishes.
 *
 * <p>The vectors are the AES samples of ASC X9 TR-34-2019 Annex B, in
 * {@code tr34-vectors/} beside this test's resources. They are the standard's own
 * byte values, not ones this branch produced, so a codec that drifts from the
 * published encoding fails here even when it still round-trips its own blocks.
 *
 * <p>What is under test is the key block reader. The first sample is a key block in
 * the clear and the second is one inside an envelope, so the reader answers two key
 * blocks the standard published rather than one.
 */
public class PaymentCryptographyTr34VectorTest {

    private static final HexFormat HEX = HexFormat.of();

    /** The key the standard's AES samples carry, in clear. */
    private static final String STANDARD_KEY = "0123456789ABCDEFFEDCBA9876543210";

    /** The digests the standard's samples have, so a changed vector is named. */
    private static final Map<String, String> DIGESTS = Map.of(
            "tr34-2019-annex-b-aes-key-block.der",
            "eceeedaa129dc583df2ac6a0d68d9b89247ea9e18b005eff4cdb617a5f0e7fc2",
            "tr34-2019-annex-b-aes-enveloped-data.der",
            "fb3e8b94bf22c4b456804d7145832315e4366937296c773f5cbc26dd39adbf71");

    @Test
    void readsTheStandardAesKeyBlock() throws Exception {
        // B.2.2.2.4: the clear AES key block, with its header in the clear.
        Tr34.Recovered recovered = Tr34.readKeyBlock(vector("tr34-2019-annex-b-aes-key-block.der"));
        assertEquals(STANDARD_KEY, recovered.keyHex);
        assertEquals("D0256K0AB00E0000", recovered.header);
    }

    @Test
    void opensTheStandardAesEnvelopedDataUnderThePublishedEphemeralKey() throws Exception {
        // B.2.2.3.2: the AES key block encrypted under the published ephemeral key.
        //
        // The sample is opened here rather than by the codec's reader, because the
        // sample is a standard CMS EnvelopedData and the reader takes the shape the
        // reference writes. What the reader is given is the key block the sample
        // carries, which is the codec under test.
        byte[] envelope = vector("tr34-2019-annex-b-aes-enveloped-data.der");
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
     * <p>The sample is a standard CMS EnvelopedData: a sequence of the version, the
     * recipients and the encrypted content information, and that last one names the
     * content encryption and carries the IV and the ciphertext beside the algorithm's
     * name.
     *
     * <p>This is not the shape the codec's reader takes. The reference writes the
     * envelope as three bare objects, the version, the recipients and the encrypted
     * content information, and the reader reads it that way, so it would refuse this
     * sample.
     */
    private static ASN1Sequence contentEncryption(byte[] envelope) {
        ASN1Sequence encryptedContentInfo = ASN1Sequence.getInstance(
                ASN1Sequence.getInstance(envelope).getObjectAt(2));
        return ASN1Sequence.getInstance(encryptedContentInfo.getObjectAt(1));
    }

    /**
     * A vector, by the file name beside this test's resources.
     *
     * <p>The digest is checked as the vector is read, so one that was altered or
     * mangled in the tree is named as such rather than failing as a parse error.
     */
    private static byte[] vector(String name) throws Exception {
        byte[] bytes;
        try (InputStream in = PaymentCryptographyTr34VectorTest.class
                .getResourceAsStream("tr34-vectors/" + name)) {
            bytes = in.readAllBytes();
        }
        assertEquals(DIGESTS.get(name), HEX.formatHex(Tr34.digest(bytes)),
                "the vector " + name + " is not the one the standard published");
        return bytes;
    }
}
