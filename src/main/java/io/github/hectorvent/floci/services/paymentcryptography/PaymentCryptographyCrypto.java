package io.github.hectorvent.floci.services.paymentcryptography;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The cryptography a key record needs: its material, and its check value.
 *
 * <p>The check value of an AES key is the leftmost three bytes of the CMAC of
 * sixteen zero bytes under the key. That is what the service computes for a
 * CMAC check value, and what the reference implementation its samples use
 * computes.
 */
final class PaymentCryptographyCrypto {

    private static final byte[] ZERO_BLOCK = new byte[16];

    private PaymentCryptographyCrypto() {
    }

    /** Fresh AES-256 material, as hexadecimal. */
    static String randomAes256Hex(SecureRandom random) {
        byte[] material = new byte[32];
        random.nextBytes(material);
        return toHex(material);
    }

    /** The check value of an AES key: the leftmost three bytes of the CMAC of sixteen zero bytes. */
    static String checkValue(String keyHex) {
        byte[] mac = cmac(fromHex(keyHex), ZERO_BLOCK);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return toHex(value).toUpperCase(Locale.ROOT);
    }

    /**
     * The AES-CMAC of a message under a key.
     *
     * <p>Only the one-block case is needed here, so the subkey derivation stops
     * at K1: the message is a single padded block.
     */
    private static byte[] cmac(byte[] key, byte[] message) {
        try {
            byte[] padded = new byte[16];
            System.arraycopy(message, 0, padded, 0, message.length);
            return encrypt(xor(padded, subkeyOne(key)), key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * The first CMAC subkey.
     *
     * <p>It is the block cipher's output over a zero block, shifted left by one
     * bit, with Rijndael's Rb exclusive-ored in when the shift carried out of
     * the top.
     */
    private static byte[] subkeyOne(byte[] key) {
        try {
            byte[] s = encrypt(ZERO_BLOCK, key);
            byte[] k1 = new byte[16];
            int carry = 0;
            for (int i = 15; i >= 0; i--) {
                // The bit that falls off the top of the block comes from the
                // byte above it, so the carry travels down the block.
                int nextCarry = (s[i] & 0x80) != 0 ? 1 : 0;
                k1[i] = (byte) (((s[i] << 1) | carry) & 0xff);
                carry = nextCarry;
            }
            if ((s[0] & 0x80) != 0) {
                k1[15] ^= (byte) 0x87;
            }
            return k1;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    private static byte[] xor(byte[] left, byte[] right) {
        byte[] out = new byte[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) (left[i] ^ right[i % right.length]);
        }
        return out;
    }

    /** One block of AES in ECB mode. Payment Cryptography uses it only for the check value. */
    private static byte[] encrypt(byte[] input, byte[] key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(input);
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0x0f, 16));
            sb.append(Character.forDigit(b & 0x0f, 16));
        }
        return sb.toString();
    }

    static byte[] fromHex(String hex) {
        int length = hex.length() / 2;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
