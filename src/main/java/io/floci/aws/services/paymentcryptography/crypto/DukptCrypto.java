package io.floci.aws.services.paymentcryptography.crypto;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * ANSI X9.24-1 TDES DUKPT - Derived Unique Key Per Transaction.
 *
 * <pre>
 *   1. IPEK derivation        BDK + initial KSN        -&gt; IPEK
 *   2. Transaction key        IPEK + KSN counter bits  -&gt; derived key (one-way)
 *   3. Working-key variant    derived key XOR mask     -&gt; MAC-request key
 * </pre>
 *
 * <p>Validated against the vectors ANSI X9.24-1 publishes, which the test holds: the
 * initial sequence of the standard's annex A.4.2. TDES only: AES DUKPT (X9.24-3) is
 * out of scope.
 *
 * <p>The odd parity the standard puts on the key bytes is not enforced here. The cipher
 * ignores the parity bits, and the published vectors carry parity-bearing keys, so the
 * derivation holds for the keys the standard defines.
 */
public final class DukptCrypto {

    private static final byte[] KEY_VARIANT = HexFormat.of().parseHex("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] MAC_REQUEST_MASK = HexFormat.of().parseHex("000000000000FF00000000000000FF00");

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
        return HexFormat.of().parseHex(padded.toString());
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
        byte[] bdk = HexFormat.of().parseHex(bdkHex);
        if (bdk.length != 16) {
            throw new IllegalArgumentException("BDK must be a 16-byte (double-length TDES) key");
        }
        byte[] left8 = Arrays.copyOfRange(zeroCounter(normalizeKsn(ksnHex)), 0, 8);
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
        byte[] reg = Arrays.copyOfRange(zeroCounter(ksn), 2, 10);
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

    /** The MAC-request working key. */
    public static byte[] macKey(String bdkHex, String ksnHex) {
        return Ciphers.xor(deriveTransactionKey(bdkHex, ksnHex), MAC_REQUEST_MASK);
    }

    private static byte[] right(byte[] key16, byte[] reg8) {
        byte[] kl = Arrays.copyOfRange(key16, 0, 8);
        byte[] kr = Arrays.copyOfRange(key16, 8, 16);
        return Ciphers.xor(Ciphers.tdesSingleDesEncrypt(Ciphers.xor(reg8, kr), kl), kr);
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
