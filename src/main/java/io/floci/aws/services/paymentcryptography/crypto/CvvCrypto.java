package io.floci.aws.services.paymentcryptography.crypto;

import io.floci.aws.core.common.Digests;

import java.util.HexFormat;

/**
 * Visa CVV / Mastercard CVC - the standard X9 card-verification algorithm.
 *
 * <p>One routine serves CVV1, CVV2 (service code 000) and iCVV (service code
 * 999); only the inputs differ.
 *
 * <pre>
 *   data = (PAN || expiry || serviceCode), right-padded with '0' to 32 hex
 *   B1, B2 = the two 8-byte blocks
 *   r   = 3DES_EDE(CVK, DES_e(B1, CVK-A) XOR B2)      with CVK = CVK-A || CVK-B
 *   value = decimalize(r, 3)
 * </pre>
 */
public final class CvvCrypto {

    /** The hexadecimal the data plane's blobs are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private CvvCrypto() {
    }

    /** Compute the 3-digit card verification value. {@code cvkHex} is CVK-A || CVK-B. */
    public static String computeCvv(String pan, String expiryYYMM, String serviceCode, String cvkHex) {
        byte[] cvk = HEX.parseHex(cvkHex);
        if (cvk.length != 16) {
            throw new IllegalArgumentException("CVK must be 16 bytes (32 hex)");
        }
        return decimalize(PinCrypto.digits(pan) + PinCrypto.digits(expiryYYMM)
                + PinCrypto.digits(serviceCode), cvk);
    }

    /**
     * The dynamic card verification value (dCVV).
     *
     * <p>A session key is derived from the EMV master key and the terminal's
     * application transaction counter, and the card verification cipher is
     * applied to the card data under that session key rather than under the
     * CVK. The value is therefore bound to one transaction, and a different
     * counter gives a different value.
     *
     * <pre>
     *   SK  = the EMV common session key derived from the EMK and the ATC
     *   r   = 3DES_EDE(SK, DES_e(B1, SK-A) XOR B2)   over PAN || expiry || serviceCode
     *   value = decimalize(r, 3)
     * </pre>
     *
     * <p><b>This is the emulator's construction of the dCVV.</b> The service's
     * cipher is in the scheme books and is not published, so the bytes this
     * produces are not guaranteed to be the bytes the service produces for the
     * same inputs. What does match the service is the shape: the inputs, the
     * key type and the mode of use, and the fact that the value depends on the
     * transaction counter.
     */
    public static String computeDynamicValue(String pan, String expiryYYMM, String serviceCode,
                                              String atcHex, String emkHex) {
        byte[] emk = masterKey(emkHex);
        byte[] sessionKey = EmvCrypto.deriveSessionKey(emk, HEX.parseHex(atcHex));
        return decimalize(PinCrypto.digits(pan) + PinCrypto.digits(expiryYYMM)
                + PinCrypto.digits(serviceCode), sessionKey);
    }

    /**
     * The dynamic card verification code (dCVC).
     *
     * <p>Like the dynamic value, but diversified with the terminal's
     * unpredictable number as well as the transaction counter, and computed
     * over the track data rather than over the card's own fields.
     *
     * <pre>
     *   SK  = the session key derived from the EMK, the ATC and the
     *         unpredictable number
     *   r   = 3DES_EDE(SK, DES_e(B1, SK-A) XOR B2)   over the track data
     *   value = decimalize(r, 3)
     * </pre>
     *
     * <p>The cipher takes sixteen bytes. Track data is longer than that, so
     * when it does not fit it is folded to sixteen bytes with SHA-256 and
     * truncated.
     *
     * <p><b>As with the dynamic value, this is the emulator's construction</b>,
     * not the service's published one. The interface, the key type and the
     * mode of use match.
     */
    public static String computeDynamicCode(String trackDataHex, String atcHex,
                                            String unpredictableNumberHex, String emkHex) {
        byte[] emk = masterKey(emkHex);
        byte[] sessionKey = EmvCrypto.deriveSessionKeyMastercard(emk, HEX.parseHex(atcHex),
                HEX.parseHex(unpredictableNumberHex));
        byte[] track = HEX.parseHex(trackDataHex);
        if (track.length > 16) {
            byte[] folded = Digests.sha256(track);
            byte[] head = new byte[16];
            System.arraycopy(folded, 0, head, 0, 16);
            track = head;
        }
        return decimalize(HEX.formatHex(track), sessionKey);
    }

    public static boolean verifyCvv(String pan, String expiryYYMM, String serviceCode,
                                    String cvkHex, String cvv) {
        return computeCvv(pan, expiryYYMM, serviceCode, cvkHex).equals(cvv);
    }

    /** The EMV master key, which the service also holds as TDES-2KEY. */
    private static byte[] masterKey(String emkHex) {
        byte[] emk = HEX.parseHex(emkHex);
        if (emk.length != 16) {
            throw new IllegalArgumentException("EMK must be 16 bytes (32 hex)");
        }
        return emk;
    }

    /**
     * The card verification cipher: two blocks, the left one enciphered with
     * the key's left half, exclusive-or the right one, then enciphered again
     * with the whole key.
     *
     * @param input hexadecimal input, at most 32 characters, zero-padded
     */
    private static String decimalize(String input, byte[] key) {
        if (input.length() > 32) {
            throw new IllegalArgumentException("the input exceeds two blocks");
        }
        StringBuilder padded = new StringBuilder();
        padded.append(input);
        while (padded.length() < 32) {
            padded.append('0');
        }
        String p = padded.substring(0, 32);
        byte[] b1 = HEX.parseHex(p.substring(0, 16));
        byte[] b2 = HEX.parseHex(p.substring(16, 32));

        byte[] r = Ciphers.tdes2Encrypt(Ciphers.xor(Ciphers.tdesSingleDesEncrypt(b1, slice(key, 0, 8)), b2), key);
        return Decimalize.of(HEX.formatHex(r), 3);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }
}
