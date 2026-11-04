package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Message digests every Java runtime is required to provide, without the checked exception. */
public final class Digests {

    private static final MessageDigest MD5 = newDigest("MD5");

    private Digests() {
    }

    /** A fresh MD5 digest. Cloning the prototype costs far less than MessageDigest.getInstance. */
    public static MessageDigest md5Digest() {
        try {
            return (MessageDigest) MD5.clone();
        } catch (CloneNotSupportedException e) {
            return newDigest("MD5");
        }
    }

    public static byte[] md5(byte[] data) {
        return md5Digest().digest(data);
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    /** The MD5 digest of {@code data} as lowercase hex. */
    public static String md5Hex(byte[] data) {
        return HexFormat.of().formatHex(md5(data));
    }

    public static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    /** The SHA-1 digest of {@code data} as lowercase hex. */
    public static String sha1Hex(byte[] data) {
        return HexFormat.of().formatHex(sha1(data));
    }

    /** The SHA-1 digest of the UTF-8 bytes of {@code text} as lowercase hex. */
    public static String sha1Hex(String text) {
        return sha1Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The SHA-256 digest of {@code data} as lowercase hex. */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /** The SHA-256 digest of the UTF-8 bytes of {@code text} as lowercase hex. */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
