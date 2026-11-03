package io.github.hectorvent.floci.services.paymentcryptography.crypto;

/**
 * ISO/IEC 9797-1 MAC algorithms 1 and 3 with padding method 1 (zero-pad) - the
 * banking construction, ANSI X9.9 / X9.19, ISO 9807.
 *
 * <p>Distinct from {@link EmvCrypto#retailMac}, which is the same algorithm 3
 * under EMV's padding method 2 (mandatory {@code 0x80}). EMV and X9.19 differ
 * only in padding.
 */
public final class IsoMac {

    private IsoMac() {
    }

    /** Padding method 1: zero-pad to an 8-byte boundary. Empty input becomes one zero block. */
    public static byte[] padZero(byte[] data) {
        if (data.length == 0) {
            return new byte[8];
        }
        int rem = data.length % 8;
        if (rem == 0) {
            return data;
        }
        byte[] out = new byte[data.length + (8 - rem)];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    /** Algorithm 1 with TDES as the block cipher: H_i = TDES(K, D_i XOR H_{i-1}). */
    public static byte[] alg1(byte[] key16, byte[] data) {
        byte[] padded = padZero(data);
        byte[] h = new byte[8];
        for (int i = 0; i < padded.length; i += 8) {
            byte[] block = new byte[8];
            System.arraycopy(padded, i, block, 0, 8);
            h = Ciphers.tdes2Encrypt(Ciphers.xor(h, block), key16);
        }
        return h;
    }

    /**
     * Algorithm 3, the retail MAC: a single-DES chain under K1 for all but the
     * last block, then a final EDE under K1||K2.
     */
    public static byte[] alg3(byte[] key16, byte[] data) {
        byte[] k1 = slice(key16, 0, 8);
        byte[] padded = padZero(data);
        int nBlocks = padded.length / 8;
        byte[] h = new byte[8];
        for (int i = 0; i < nBlocks - 1; i++) {
            byte[] block = new byte[8];
            System.arraycopy(padded, i * 8, block, 0, 8);
            h = Ciphers.tdesSingleDesEncrypt(Ciphers.xor(h, block), k1);
        }
        byte[] last = new byte[8];
        System.arraycopy(padded, (nBlocks - 1) * 8, last, 0, 8);
        return Ciphers.tdes2Encrypt(Ciphers.xor(h, last), key16);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }
}
