package io.floci.aws.services.paymentcryptography.crypto;

/**
 * Byte array helpers shared across the data-plane ciphers.
 *
 * <p>Each method is static and stateless, so there is no need for an instance.
 */
public final class Bytes {

    private Bytes() {
    }

    /**
     * Concatenate byte arrays into one.
     *
     * @param parts the arrays to join, in order
     * @return a single array containing all the bytes
     */
    public static byte[] concat(byte[]... parts) {
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

    /**
     * PKCS#5/PKCS#7 padding.
     *
     * <p>A whole block is added when the input already fills one, so the
     * padding is never ambiguous.
     *
     * @param data the bytes to pad
     * @param blockSize the block size to pad to
     * @return the padded bytes
     */
    public static byte[] pkcs7Pad(byte[] data, int blockSize) {
        int padLength = blockSize - (data.length % blockSize);
        byte[] out = new byte[data.length + padLength];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = 0; i < padLength; i++) {
            out[data.length + i] = (byte) padLength;
        }
        return out;
    }

    /**
     * Strip PKCS#5/PKCS#7 padding, and refuse anything that is not properly padded.
     *
     * @param data the padded bytes
     * @param blockSize the block size the padding works to
     * @return the unpadded bytes
     * @throws IllegalArgumentException when the data is not whole-block or
     *     not properly padded
     */
    public static byte[] pkcs7Unpad(byte[] data, int blockSize) {
        if (data.length == 0 || data.length % blockSize != 0) {
            throw new IllegalArgumentException("the data is not whole blocks");
        }
        int padLength = data[data.length - 1] & 0xFF;
        if (padLength < 1 || padLength > blockSize || padLength > data.length) {
            throw new IllegalArgumentException("the padding is not valid");
        }
        for (int i = data.length - padLength; i < data.length; i++) {
            if ((data[i] & 0xFF) != padLength) {
                throw new IllegalArgumentException("the padding is not valid");
            }
        }
        byte[] out = new byte[data.length - padLength];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /**
     * Whether a string is non-empty and entirely hexadecimal.
     *
     * @param text the string to check
     * @return true if the string is a hex sequence of even length
     */
    public static boolean isHex(String text) {
        if (text == null || text.isEmpty() || text.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }
}
