package io.github.hectorvent.floci.services.paymentcryptography.crypto;

/**
 * ANSI X9.24-1 TDES DUKPT - Derived Unique Key Per Transaction.
 *
 * <pre>
 *   1. IPEK derivation        BDK + initial KSN        -&gt; IPEK
 *   2. Transaction key        IPEK + KSN counter bits  -&gt; derived key (one-way)
 *   3. Working-key variants   derived key XOR mask     -&gt; PIN / MAC-request / data key
 *      (the data key is additionally self-encrypted per X9.24-1)
 * </pre>
 *
 * <p>Validated against two independent published vector sets: the classic
 * X9.24 walkthrough, and the ID TECH worked example. TDES only - AES DUKPT
 * (X9.24-3) is out of scope, and the jPOS version in use does not implement this
 * derivation either, so parity is checked against published vectors rather than
 * against jPOS.
 */
public final class DukptCrypto {

    private static final byte[] KEY_VARIANT = Hex.decode("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] PIN_MASK = Hex.decode("00000000000000FF00000000000000FF");
    private static final byte[] MAC_REQUEST_MASK = Hex.decode("000000000000FF00000000000000FF00");
    private static final byte[] DATA_REQUEST_MASK = Hex.decode("0000000000FF00000000000000FF0000");

    private DukptCrypto() {
    }

    /**
     * Normalize a KSN to 10 bytes. A shorter KSN is left-padded with F nibbles:
     * transmitted KSNs routinely drop leading FF padding.
     */
    public static byte[] normalizeKsn(String ksnHex) {
        String h = ksnHex == null ? "" : ksnHex.toUpperCase();
        if (h.isEmpty() || !h.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException("KSN must be hexadecimal");
        }
        if (h.length() > 20) {
            throw new IllegalArgumentException("KSN must be at most 20 hex characters (10 bytes)");
        }
        StringBuilder padded = new StringBuilder(20);
        for (int i = h.length(); i < 20; i++) {
            padded.append('F');
        }
        padded.append(h);
        return Hex.decode(padded.toString());
    }

    /** The rightmost 21 bits of the KSN: the transaction counter. */
    public static int ksnCounter(byte[] ksn10) {
        return ((ksn10[7] & 0x1f) << 16) | ((ksn10[8] & 0xff) << 8) | (ksn10[9] & 0xff);
    }

    /** A copy of the KSN with the 21 counter bits zeroed. */
    public static byte[] zeroCounter(byte[] ksn10) {
        byte[] z = ksn10.clone();
        z[7] &= (byte) 0xe0;
        z[8] = 0;
        z[9] = 0;
        return z;
    }

    /**
     * {@code IPEK = TDES(BDK, left8) || TDES(BDK XOR C0C0..., left8)}, where
     * {@code left8} is the KSN with its counter cleared, first 8 bytes.
     */
    public static byte[] deriveIpek(String bdkHex, String ksnHex) {
        byte[] bdk = Hex.decode(bdkHex);
        if (bdk.length != 16) {
            throw new IllegalArgumentException("BDK must be a 16-byte (double-length TDES) key");
        }
        byte[] left8 = slice(zeroCounter(normalizeKsn(ksnHex)), 0, 8);
        return concat(
                Ciphers.tdes2Encrypt(left8, bdk),
                Ciphers.tdes2Encrypt(left8, Ciphers.xor(bdk, KEY_VARIANT)));
    }

    /** The X9.24-1 non-reversible key generation step, the single-DES "black box". */
    public static byte[] deriveStep(byte[] key16, byte[] reg8) {
        // Order matters: the variant-keyed half comes first.
        return concat(right(Ciphers.xor(key16, KEY_VARIANT), reg8), right(key16, reg8));
    }

    /**
     * The derived transaction key for the KSN's counter, by walking the set
     * counter bits from most to least significant.
     */
    public static byte[] deriveTransactionKey(String bdkHex, String ksnHex) {
        byte[] ksn = normalizeKsn(ksnHex);
        int counter = ksnCounter(ksn);
        if (counter == 0) {
            throw new IllegalArgumentException("KSN transaction counter must be non-zero");
        }
        byte[] key = deriveIpek(bdkHex, ksnHex);
        // The rightmost 8 bytes of the counter-cleared KSN, which become the
        // shift register the counter bits are written into.
        byte[] reg = slice(zeroCounter(ksn), 2, 10);
        for (int bit = 20; bit >= 0; bit--) {
            int mask = 1 << bit;
            if ((counter & mask) == 0) {
                continue;
            }
            reg[5] |= (byte) ((mask >> 16) & 0x1f);
            reg[6] |= (byte) ((mask >> 8) & 0xff);
            reg[7] |= (byte) (mask & 0xff);
            key = deriveStep(key, reg);
        }
        return key;
    }

    /** The PIN encryption working key. */
    public static byte[] pinKey(String bdkHex, String ksnHex) {
        return Ciphers.xor(deriveTransactionKey(bdkHex, ksnHex), PIN_MASK);
    }

    /** The MAC-request working key. */
    public static byte[] macKey(String bdkHex, String ksnHex) {
        return Ciphers.xor(deriveTransactionKey(bdkHex, ksnHex), MAC_REQUEST_MASK);
    }

    /**
     * The data-encryption-request working key: the variant mask is applied, then
     * each half is TDES-encrypted under the variant key itself, the X9.24-1
     * one-way step.
     */
    public static byte[] dataKey(String bdkHex, String ksnHex) {
        byte[] v = Ciphers.xor(deriveTransactionKey(bdkHex, ksnHex), DATA_REQUEST_MASK);
        return concat(
                Ciphers.tdes2Encrypt(slice(v, 0, 8), v),
                Ciphers.tdes2Encrypt(slice(v, 8, 16), v));
    }

    private static byte[] right(byte[] key16, byte[] reg8) {
        byte[] kl = slice(key16, 0, 8);
        byte[] kr = slice(key16, 8, 16);
        return Ciphers.xor(Ciphers.tdesSingleDesEncrypt(Ciphers.xor(reg8, kr), kl), kr);
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
