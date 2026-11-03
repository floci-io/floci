package io.github.hectorvent.floci.services.paymentcryptography.crypto;

/**
 * Hex encoding and decoding.
 *
 * <p>Java 11 has no {@code java.util.HexFormat} (that arrived in 17), and the
 * emulator's wire format leans on the case convention of its hex fields:
 * key material is upper-case, ciphertext and PVKs are lower-case, and the key
 * check value is upper-case. Those distinctions are contractual, so they are
 * explicit here rather than implied.
 */
public final class Hex {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    /** Encode as lower-case hex. The default for ciphertext, plaintext and PVK material. */
    public static String encode(byte[] bytes) {
        return encode(bytes, false);
    }

    /** Encode as upper-case hex. The convention for key material and key check values. */
    public static String encodeUpper(byte[] bytes) {
        return encode(bytes, true);
    }

    private static String encode(byte[] bytes, boolean upper) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes is required");
        }
        char[] table = upper ? UPPER : LOWER;
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0f];
        }
        return new String(out);
    }

    /**
     * Decode hex in either case.
     *
     * @throws IllegalArgumentException if the input is not valid hex, or has an odd
     *     length - a truncated key or ciphertext is a caller bug, not something to
     *     silently pad.
     */
    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex is required");
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex must have an even length, got " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((digit(hex.charAt(i * 2)) << 4) | digit(hex.charAt(i * 2 + 1)));
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("not a hex character: '" + c + "'");
    }

    /** True when the string is non-empty and entirely hex digits. Mirrors the JS {@code isHex}. */
    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
