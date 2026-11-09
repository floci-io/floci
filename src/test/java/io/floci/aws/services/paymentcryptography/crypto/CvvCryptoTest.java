package io.floci.aws.services.paymentcryptography.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The card verification values, against the constructions the specifications give.
 *
 * <p>The dynamic value is the one the Visa Contactless Payment Specification describes: the
 * leftmost four digits of the account number are replaced by the transaction counter, and
 * the result, the expiry and the service code are enciphered under the card's unique
 * derived key. The expected value is computed here from the specification's own steps,
 * with the cipher written out, so that a change to the service is a change against the
 * specification rather than against itself.
 */
class CvvCryptoTest {

    /** The issuer master key the unique derived key is derived from. */
    private static final String IMK = "0123456789ABCDEFFEDCBA9876543210";

    /** The account number of the card. */
    private static final String PAN = "5081470082639564";

    /** The expiry of the card, in the YYMM form the request carries. */
    private static final String EXPIRY = "3003";

    /** The service code of the value. */
    private static final String SERVICE_CODE = "201";

    /** The transaction counter, in the binary coded decimal the track data carries. */
    private static final String ATC = "0001";

    /** The PAN sequence number, which the unique derived key is derived with. */
    private static final String PSN = "01";

    @Test
    void theDynamicValueIsTheOneTheSpecificationGives() {
        assertEquals(dcvv(), CvvCrypto.computeDynamicValue(
                PAN, EXPIRY, SERVICE_CODE, ATC, IMK, PSN));
    }

    @Test
    void theDynamicValueIsNotTheStaticValue() {
        // The counter is inserted into the account number, so the value is bound to the
        // transaction and is not the value of the card's fields.
        assertEquals(false, CvvCrypto.computeDynamicValue(PAN, EXPIRY, SERVICE_CODE, ATC, IMK, PSN)
                .equals(CvvCrypto.computeCvv(PAN, EXPIRY, SERVICE_CODE, IMK)));
    }

    /**
     * The dynamic value, written from the specification's steps.
     *
     * <ol>
     *   <li>The leftmost four digits of the account number are replaced by the counter.</li>
     *   <li>The result, the expiry and the service code are enciphered under the unique
     *       derived key: the left block is enciphered with the first half of the key, the
     *       result is exclusive-ored with the right block, and the block is enciphered,
     *       deciphered and enciphered with the two halves of the key.</li>
     *   <li>The three leftmost decimal digits of the result are the value.</li>
     * </ol>
     */
    private static String dcvv() {
        String data = ATC + PAN.substring(4) + EXPIRY + SERVICE_CODE;
        while (data.length() < 32) {
            data = data + "0";
        }
        byte[] left = hex(data.substring(0, 16));
        byte[] right = hex(data.substring(16, 32));
        byte[] udk = EmvCrypto.deriveIccMasterKey(hex(IMK), PAN, PSN);

        byte[] block = singleDes(left, udk, 0, false);
        block = xor(block, right);
        block = singleDes(block, udk, 0, false);
        block = singleDes(block, udk, 8, true);
        block = singleDes(block, udk, 0, false);

        StringBuilder digits = new StringBuilder();
        StringBuilder letters = new StringBuilder();
        for (byte b : block) {
            for (int nibble : new int[]{(b >> 4) & 0xf, b & 0xf}) {
                if (nibble < 10) {
                    digits.append((char) ('0' + nibble));
                } else {
                    letters.append((char) ('0' + nibble - 10));
                }
            }
        }
        return digits.append(letters).substring(0, 3);
    }

    /** One block of Single DES, with the first or the second half of the double length key. */
    private static byte[] singleDes(byte[] block, byte[] key16, int half, boolean decrypt) {
        byte[] key = new byte[8];
        System.arraycopy(key16, half, key, 0, 8);
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(decrypt ? Cipher.DECRYPT_MODE : Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "DES"));
            return cipher.doFinal(block);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exclusive-or of two byte arrays of the same length. */
    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /** The bytes of a hexadecimal string. */
    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
