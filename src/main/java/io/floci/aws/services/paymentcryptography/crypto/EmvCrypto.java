package io.floci.aws.services.paymentcryptography.crypto;

import io.floci.aws.core.common.Digests;

import java.util.HexFormat;

/**
 * The canonical EMV ARQC/ARPC spine, and the primitives it rests on.
 *
 * <pre>
 *   1. ICC master key derivation (EMV Book 2, Option A)   IMK + PAN + PSN -&gt; MK
 *   2. Common session key derivation (EMV Book 2 A1.3.1)  MK  + ATC       -&gt; SK
 *   3. ARQC = ISO 9797-1 MAC algorithm 3 over the transaction data, truncated to 8 bytes
 *   4. ARPC method 1 = 3DES_EDE(SK, ARQC XOR (ARC || 00*6))
 * </pre>
 *
 * <p>The transaction data reaches the service already padded, because the caller pads it
 * the way the card padded it before the card enciphered it. The service enciphers it as
 * it was sent, and the padding the service applies is the one it applies to the input it
 * builds itself, which is the issuer authentication cryptogram.
 *
 * <p>Each cipher says which one it uses: the retail MAC is single DES, and the
 * key derivations are two-key TDES, which the cipher takes as K1||K2||K1.
 *
 * <p>The full PAN flows into master key derivation, because the derivation is
 * defined over the whole of it.
 */
public final class EmvCrypto {

    /** The hexadecimal the data plane's blobs are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private EmvCrypto() {
    }

    // ---- primitives ---------------------------------------------------------

    /** Force every DES key byte to odd parity. */
    public static byte[] adjustDesParity(byte[] key) {
        byte[] out = new byte[key.length];
        for (int i = 0; i < key.length; i++) {
            int b = key[i] & 0xff;
            int p = 0;
            // The parity bit is bit 0; XOR bits 1..7, then force the byte odd.
            for (int bit = 1; bit < 8; bit++) {
                p ^= (b >> bit) & 1;
            }
            out[i] = (byte) ((b & 0xfe) | ((p ^ 0x01) & 0x01));
        }
        return out;
    }

    /**
     * The ISO 9797-1 padding method used for an application cryptogram.
     *
     * <p>The transaction data reaches the service already padded: the user guide tells
     * the caller to pad it before sending it, because the card padded it the same way
     * before it enciphered it. The service therefore enciphers the data as it was
     * given, and padding it again would encipher a block the card never enciphered.
     */
    public enum Padding {
        /**
         * No padding: the data is enciphered exactly as it was given.
         */
        NONE,
        /**
         * Method 2: append {@code 0x80}, then zeros to the next boundary,
         * always adding a block even when the data is already aligned. The input of
         * an issuer authentication cryptogram is built here rather than by the
         * caller, so it is this one that is padded.
         */
        METHOD2
    }

    /** Pad according to the given method. */
    public static byte[] pad(byte[] data, Padding padding) {
        return switch (padding) {
            case NONE -> data;
            case METHOD2 -> pad80(data);
        };
    }

    /**
     * ISO/IEC 9797-1 padding method 2: append {@code 0x80}, then zeros to the
     * next 8-byte boundary. Always pads a block, so a block-aligned input gains
     * a whole extra block.
     */
    public static byte[] pad80(byte[] data) {
        int total = data.length - (data.length % 8) + 8;
        byte[] out = new byte[total];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) 0x80;
        return out;
    }

    /**
     * The retail MAC over data padded with the given ISO 9797-1 method.
     *
     * <p>This is ISO/IEC 9797-1 MAC algorithm 3 with a 16-byte key, and it is
     * single-DES chaining, not three-key TDES. The key splits into K1 (left 8)
     * and K2 (right 8); each block is enciphered under K1, and a single D_KR
     * then E_KL final transform closes the tag:
     *
     * <pre>
     *   H_i  = E_KL(D_i XOR H_{i-1})   for i = 1..n
     *   MAC  = E_KL(D_KR(H_n))
     * </pre>
     *
     * <p>Running a 3DES transform per block instead - the obvious reading -
     * produces a different MAC.
     */
    public static byte[] retailMac(byte[] key16, byte[] data, Padding padding) {
        byte[] kl = slice(key16, 0, 8);
        byte[] kr = slice(key16, 8, 16);
        byte[] padded = pad(data, padding);
        int nBlocks = padded.length / 8;
        byte[] h = new byte[8];
        for (int i = 0; i < nBlocks; i++) {
            h = Ciphers.tdesSingleDesEncrypt(Ciphers.xor(h, slice(padded, i * 8, i * 8 + 8)), kl);
        }
        return Ciphers.tdesSingleDesEncrypt(Ciphers.tdesSingleDesDecrypt(h, kr), kl);
    }

    // ---- EMV key derivation -------------------------------------------------

    /**
     * EMV Book 2 Option A ICC master key derivation.
     *
     * <pre>
     *   Y  = the RIGHT-MOST 16 digits of PAN || PSN, left-padded with zeros when shorter
     *   ZR = Y XOR FF..FF
     *   MK = 3DES(IMK, Y) || 3DES(IMK, ZR), odd parity adjusted
     * </pre>
     *
     * <p>Y is the last sixteen digits, not the first. For a 16-digit PAN the two differ,
     * and using the first changes the master key and every tag that follows. The rule is the
     * one EMV 4.4 Book 2 Annex A1.4.1 gives, and the Visa Integrated Circuit Card
     * Specification states it the same way: the rightmost sixteen digits of the PAN and the
     * sequence number, padded on the left with zeros when they are shorter.
     */
    public static byte[] deriveIccMasterKey(byte[] imk16, String pan, String psn) {
        byte[] y = masterKeyInput(pan, psn);
        byte[] zr = new byte[8];
        for (int i = 0; i < 8; i++) {
            zr[i] = (byte) (y[i] ^ 0xff);
        }
        return adjustDesParity(concat(
                Ciphers.tdes2Encrypt(y, imk16),
                Ciphers.tdes2Encrypt(zr, imk16)));
    }

    /**
     * EMV Book 2 Option B master key derivation, for PANs longer than 16 digits.
     *
     * <pre>
     *   H  = SHA-1(PAN || PSN as packed BCD)
     *   Y  = the leftmost 16 digits of {H's decimal digits || H's other nibbles mod 10}
     *   MK = 3DES(IMK, Y) || 3DES(IMK, Y XOR FF..FF)
     * </pre>
     *
     * <p>The decimal digits of the hash come first, in the order they appear, and the
     * nibbles that are not decimal digits follow them, each reduced modulo ten. A hash
     * of a packed account number and sequence number holds sixteen decimal digits
     * almost every time, so the second part is rarely reached.
     *
     * <p>A PAN of sixteen digits or fewer is derived the Option A way: the hash is
     * only there for the longer numbers.
     */
    public static byte[] deriveIccMasterKeyOptionB(byte[] imk16, String pan, String psn) {
        if (PinCrypto.digits(pan).length() <= 16) {
            return deriveIccMasterKey(imk16, pan, psn);
        }
        byte[] b = packedBcd(pan, psn);
        String hash = HEX.formatHex(Digests.sha1(b));
        StringBuilder decimal = new StringBuilder(hash.length());
        StringBuilder other = new StringBuilder(hash.length());
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (c >= '0' && c <= '9') {
                decimal.append(c);
            } else {
                other.append((char) ('0' + (c - 'A')));
            }
        }
        StringBuilder y = new StringBuilder(decimal).append(other);
        if (y.length() < 16) {
            throw new IllegalArgumentException("Option B: the SHA-1 hash yields fewer than 16 digits");
        }
        byte[] zl = HEX.parseHex(y.substring(0, 16));
        byte[] zr = new byte[8];
        for (int i = 0; i < 8; i++) {
            zr[i] = (byte) (zl[i] ^ 0xff);
        }
        return adjustDesParity(concat(
                Ciphers.tdes2Encrypt(zl, imk16),
                Ciphers.tdes2Encrypt(zr, imk16)));
    }

    /**
     * The EMV common session key (EMV 4.2 Book 2 Annex A1.3.1).
     *
     * <pre>
     *   F1 = ATC(2) || F0 || 00*5     SK_L = 3DES(MK, F1)
     *   F2 = ATC(2) || 0F || 00*5     SK_R = 3DES(MK, F2)
     * </pre>
     *
     * <p>Each half carries odd DES parity.
     */
    public static byte[] deriveSessionKey(byte[] mk16, byte[] atc) {
        byte[] r = new byte[8];
        System.arraycopy(atc, 0, r, 0, Math.min(2, atc.length));
        return sessionKeyFromDiversifier(mk16, r);
    }

    /**
     * The MasterCard proprietary session key derivation (M/Chip).
     *
     * <p>Identical to the EMV common session key except for the 8-byte
     * diversification input, which here is the low two bytes of the application
     * transaction counter followed by the low four bytes of the terminal's
     * unpredictable number, rather than the counter followed by zeros.
     *
     * <p>Both halves still carry odd DES parity, as the MasterCard method
     * requires.
     *
     * @param unpredictableNumber the terminal-generated unpredictable number; its
     *     low four bytes are used
     */
    public static byte[] deriveSessionKeyMastercard(byte[] mk16, byte[] atc,
                                                   byte[] unpredictableNumber) {
        byte[] r = new byte[8];
        // The LOW bytes, so an over-long counter or
        // unpredictable number gives the same result on both sides.
        int atcFrom = Math.max(0, atc.length - 2);
        System.arraycopy(atc, atcFrom, r, 0, Math.min(2, atc.length));
        int unFrom = Math.max(0, unpredictableNumber.length - 4);
        System.arraycopy(unpredictableNumber, unFrom, r, 4, Math.min(4, unpredictableNumber.length));
        return sessionKeyFromDiversifier(mk16, r);
    }

    /** The shared tail of both derivations: the 0xF0/0x0F halves, enciphered, parity adjusted. */
    private static byte[] sessionKeyFromDiversifier(byte[] mk16, byte[] r) {
        byte[] f1 = r.clone();
        f1[2] = (byte) 0xf0;
        byte[] f2 = r.clone();
        f2[2] = (byte) 0x0f;
        return adjustDesParity(concat(
                Ciphers.tdes2Encrypt(f1, mk16),
                Ciphers.tdes2Encrypt(f2, mk16)));
    }

    // ---- cryptograms --------------------------------------------------------

    /** An ARQC together with the session key it was computed under. */
    public static final class Arqc {
        public final byte[] arqc;
        public final byte[] sessionKey;

        public Arqc(byte[] arqc, byte[] sessionKey) {
            this.arqc = arqc;
            this.sessionKey = sessionKey;
        }
    }

    /**
     * ARPC method 1: {@code 3DES_EDE(SK, ARQC XOR (ARC || 00*6))}.
     *
     * @param arcHex the authorization response code, right-padded to 8 bytes
     */
    public static byte[] computeArpcMethod1(byte[] sk, byte[] arqc, String arcHex) {
        byte[] arc = HEX.parseHex(arcHex);
        byte[] x = new byte[8];
        System.arraycopy(arc, 0, x, 0, Math.min(8, arc.length));
        return Ciphers.tdes2Encrypt(Ciphers.xor(arqc, x), sk);
    }

    /**
     * ARPC method 2, the CSU-based one: the leftmost 4 bytes of the retail MAC
     * over {@code ARQC || CSU || proprietary} under the session key.
     *
     * <p>The response code belongs in the input of the first method. The second
     * method has none: the card status update and the proprietary data are what
     * it is made from, and EMV Book 2 section 8.2.2 says so.
     *
     * <p>The request carries no way to ask for the other form: the model gives
     * the card status update and the proprietary data, and nothing else.
     */
    public static byte[] computeArpcMethod2(byte[] sk, byte[] arqc,
                                            String csuHex, String padHex) {
        byte[] data = concat(
                arqc,
                csuHex == null ? new byte[0] : HEX.parseHex(csuHex),
                padHex == null ? new byte[0] : HEX.parseHex(padHex));
        return slice(retailMac(sk, data, Padding.METHOD2), 0, 4);
    }

    // ---- helpers ------------------------------------------------------------

    /** PAN concatenated with PSN as packed BCD, left-zero-padded to an even nibble count. */
    static byte[] packedBcd(String pan, String psn) {
        String digits = PinCrypto.digits(pan) + PinCrypto.digits(psn == null ? "00" : psn);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("PAN and PSN must contain digits");
        }
        if (digits.length() % 2 != 0) {
            digits = "0" + digits;
        }
        return HEX.parseHex(digits);
    }

    /**
     * The block the ICC master key is derived over: the rightmost sixteen digits of the PAN
     * and the PAN sequence number, padded on the left with zeros when they are shorter.
     */
    private static byte[] masterKeyInput(String pan, String psn) {
        String digits = PinCrypto.digits(pan) + PinCrypto.digits(psn == null ? "00" : psn);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("PAN and PSN must contain digits");
        }
        if (digits.length() > 16) {
            digits = digits.substring(digits.length() - 16);
        }
        while (digits.length() < 16) {
            digits = "0" + digits;
        }
        return HEX.parseHex(digits);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
