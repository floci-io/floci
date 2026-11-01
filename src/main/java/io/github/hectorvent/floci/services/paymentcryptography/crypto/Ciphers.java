package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The block-cipher primitives, on JCE.
 *
 * <p>Every transformation is pinned to an explicit algorithm, mode and padding
 * string. JCE defaults differ between providers, and a default that changes is
 * a silent change to every value this emulator returns.
 *
 * <p>{@code NoPadding} is not a style choice: it is what makes a ciphertext the
 * same length as its plaintext, which the data-plane length-in-bits responses
 * depend on.
 */
public final class Ciphers {

    private Ciphers() {
    }

    // ---- TDES ----------------------------------------------------------------

    /**
     * Widen a two-key (16-byte) TDES key to three-key form by appending its own
     * first 8 bytes: K1||K2 -&gt; K1||K2||K1.
     *
     * <p>Required, not cosmetic. JCE rejects a 16-byte {@code DESede} key spec
     * outright, and Node's {@code des-ede3-cbc} does too - only its ECB path
     * accepts one. Widening explicitly means both modes behave identically here
     * and the two implementations cannot drift.
     */
    public static byte[] widenTdesKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (key.length == 24) {
            return key;
        }
        if (key.length != 16) {
            throw new IllegalArgumentException(
                    "TDES key must be 16 or 24 bytes, got " + key.length);
        }
        byte[] out = new byte[24];
        System.arraycopy(key, 0, out, 0, 16);
        System.arraycopy(key, 0, out, 16, 8);
        return out;
    }

    /** TDES on a single 8-byte block, with K||K||K, as the JavaScript does for single DES. */
    public static byte[] tdesSingleDesEncrypt(byte[] block8, byte[] key8) {
        return tdesEcbEncrypt(block8, concat(key8, key8, key8));
    }

    public static byte[] tdesSingleDesDecrypt(byte[] block8, byte[] key8) {
        return tdesEcbDecrypt(block8, concat(key8, key8, key8));
    }

    /** Two-key TDES EDE (E_K1 D_K2 E_K1) on one 8-byte block. Widens {@code key16} first. */
    public static byte[] tdes2Encrypt(byte[] block8, byte[] key16) {
        return tdesEcbEncrypt(block8, widenTdesKey(key16));
    }

    /** TDES ECB, no padding. Accepts a 16- or 24-byte key. */
    public static byte[] tdesEcbEncrypt(byte[] data, byte[] key) {
        return tdes(Cipher.ENCRYPT_MODE, data, widenTdesKey(key), null);
    }

    public static byte[] tdesEcbDecrypt(byte[] data, byte[] key) {
        return tdes(Cipher.DECRYPT_MODE, data, widenTdesKey(key), null);
    }

    /** TDES CBC, no padding. {@code iv} is 8 bytes. */
    public static byte[] tdesCbcEncrypt(byte[] data, byte[] key, byte[] iv) {
        return tdes(Cipher.ENCRYPT_MODE, data, widenTdesKey(key), iv);
    }

    public static byte[] tdesCbcDecrypt(byte[] data, byte[] key, byte[] iv) {
        return tdes(Cipher.DECRYPT_MODE, data, widenTdesKey(key), iv);
    }

    private static byte[] tdes(int mode, byte[] data, byte[] key24, byte[] iv) {
        return run("DESede", mode, data, key24, iv, "DESede");
    }

    // ---- AES -----------------------------------------------------------------

    public static byte[] aesEcbEncrypt(byte[] data, byte[] key) {
        return aes(Cipher.ENCRYPT_MODE, data, key, null);
    }

    public static byte[] aesEcbDecrypt(byte[] data, byte[] key) {
        return aes(Cipher.DECRYPT_MODE, data, key, null);
    }

    public static byte[] aesCbcEncrypt(byte[] data, byte[] key, byte[] iv) {
        return aes(Cipher.ENCRYPT_MODE, data, key, iv);
    }

    public static byte[] aesCbcDecrypt(byte[] data, byte[] key, byte[] iv) {
        return aes(Cipher.DECRYPT_MODE, data, key, iv);
    }

    private static byte[] aes(int mode, byte[] data, byte[] key, byte[] iv) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException(
                    "AES key must be 16, 24 or 32 bytes, got " + (key == null ? "null" : key.length));
        }
        return run("AES", mode, data, key, iv, "AES");
    }

    // ---- digests -------------------------------------------------------------

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    public static byte[] sha512(byte[] data) {
        return digest("SHA-512", data);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required by the JDK", e);
        }
    }

    // ---- shared --------------------------------------------------------------

    private static byte[] run(String transformation, int mode, byte[] data, byte[] key,
                              byte[] iv, String keyAlgorithm) {
        try {
            String spec = iv == null
                    ? keyAlgorithm + "/ECB/NoPadding"
                    : keyAlgorithm + "/CBC/NoPadding";
            Cipher cipher = Cipher.getInstance(spec);
            if (iv == null) {
                cipher.init(mode, new SecretKeySpec(key, keyAlgorithm));
            } else {
                cipher.init(mode, new SecretKeySpec(key, keyAlgorithm), new IvParameterSpec(iv));
            }
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(transformation + " failed", e);
        }
    }

    /** Byte-wise exclusive or over the shorter of the two operands, matching the JS helper. */
    public static byte[] xor(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /** Byte-wise exclusive or over the longer operand, treating absent bytes as zero. */
    public static byte[] xorPad(byte[] a, byte[] b) {
        int n = Math.max(a.length, b.length);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((i < a.length ? a[i] : 0) ^ (i < b.length ? b[i] : 0));
        }
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
