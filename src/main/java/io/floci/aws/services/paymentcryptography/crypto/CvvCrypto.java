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
    public static String computeCvv(String pan, String expiryYYMM, String serviceCode,
                                      String cvkHex, int length) {
        byte[] cvk = HEX.parseHex(cvkHex);
        if (cvk.length != 16) {
            throw new IllegalArgumentException("CVK must be 16 bytes (32 hex)");
        }
        return decimalize(PinCrypto.digits(pan) + PinCrypto.digits(expiryYYMM)
                + PinCrypto.digits(serviceCode), cvk, length);
    }

    /**
     * The dynamic card verification value (dCVV).
     *
     * <p>Visa's construction: the leftmost four digits of the account number are replaced by
     * the transaction counter, and the result, the expiry and the service code are
     * enciphered under the card's unique derived key. The three leftmost decimal digits of
     * the result are the value.
     *
     * <pre>
     *   altered PAN = the leftmost four digits of the PAN replaced by the counter
     *   r   = 3DES_EDE(UDK, DES_e(B1, UDK-A) XOR B2)  over altered PAN || expiry || service code
     *   value = decimalize(r, 3)
     * </pre>
     *
     * <p>The key is the unique derived key the card is personalised with, which the issuer
     * derives from the master key with the account number and the PAN sequence number. The
     * counter diversifies the value by the data it is inserted into, not by the key.
     */
    public static String computeDynamicValue(String pan, String expiryYYMM, String serviceCode,
                                              String atcHex, String emkHex, String psn,
                                              int length) {
        byte[] udk = EmvCrypto.deriveIccMasterKey(HEX.parseHex(emkHex), pan, psn);
        return decimalize(alteredPan(pan, atcHex) + PinCrypto.digits(expiryYYMM)
                + PinCrypto.digits(serviceCode), udk, length);
    }

    /**
     * The account number with its leftmost four digits replaced by the transaction counter.
     *
     * <p>The counter is a binary coded decimal counter, so its digits are the four decimal
     * digits of its bytes, and only the rightmost four of them are inserted.
     */
    private static String alteredPan(String pan, String atcHex) {
        String digits = PinCrypto.digits(pan);
        String counter = atcHex;
        if (counter.length() > 4) {
            counter = counter.substring(counter.length() - 4);
        }
        StringBuilder altered = new StringBuilder(counter.length() + digits.length());
        for (int i = 0; i < counter.length(); i++) {
            char c = counter.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "the transaction counter must be a binary coded decimal counter");
            }
            altered.append(c);
        }
        while (altered.length() < 4) {
            altered.insert(0, '0');
        }
        return altered.append(digits.substring(4)).toString();
    }

    /**
     * The card verification cipher: two blocks, the left one enciphered with
     * the key's left half, exclusive-or the right one, then enciphered again
     * with the whole key.
     *
     * @param input hexadecimal input, at most 32 characters, zero-padded
     */
    private static String decimalize(String input, byte[] key, int length) {
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
        return Decimalize.of(HEX.formatHex(r), length);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }
}
