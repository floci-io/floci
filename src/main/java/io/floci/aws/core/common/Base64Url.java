package io.floci.aws.core.common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Base64url without padding, the encoding of JWT segments and WebAuthn fields. */
public final class Base64Url {

    private Base64Url() {
    }

    public static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Encodes the UTF-8 bytes of {@code text}. */
    public static String encode(String text) {
        return encode(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encodes a non-negative integer as its unsigned big-endian bytes, the form a JWK carries an RSA
     * modulus and exponent in (RFC 7518, section 6.3.1): the shortest one, without the leading zero
     * byte {@link BigInteger#toByteArray()} adds when the top bit is set.
     */
    public static String encodeUnsigned(BigInteger value) {
        return encode(unsignedBytes(value));
    }

    /**
     * Encodes a non-negative integer as exactly {@code length} unsigned big-endian bytes, the form a
     * JWK carries an EC coordinate in (RFC 7518, section 6.2.1.2): "the full coordinate size, even if
     * that octet string is zero-padded on the left", which the shortest form misses for about one
     * value in 256.
     *
     * @throws IllegalArgumentException if {@code value} needs more than {@code length} bytes
     */
    public static String encodeUnsigned(BigInteger value, int length) {
        byte[] bytes = unsignedBytes(value);
        if (bytes.length > length) {
            throw new IllegalArgumentException(
                    "Value needs " + bytes.length + " bytes, more than the " + length + " allowed");
        }
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
        return encode(padded);
    }

    private static byte[] unsignedBytes(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Value is negative");
        }
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    /**
     * Decodes {@code text}, padded or not, as UTF-8.
     *
     * @throws IllegalArgumentException if {@code text} is not base64url
     */
    public static String decode(String text) {
        return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
