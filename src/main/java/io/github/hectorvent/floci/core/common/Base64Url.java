package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
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
     * Decodes {@code text}, padded or not, as UTF-8.
     *
     * @throws IllegalArgumentException if {@code text} is not base64url
     */
    public static String decode(String text) {
        return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
