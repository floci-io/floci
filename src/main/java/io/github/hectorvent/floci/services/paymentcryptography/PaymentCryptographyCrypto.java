package io.github.hectorvent.floci.services.paymentcryptography;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * The cryptography a key record needs: its material, and its check value.
 *
 * <p>The check value of an AES key is the leftmost three bytes of the CMAC of
 * sixteen zero bytes under the key. That is what the service computes for a
 * CMAC check value, and what the reference implementation its samples use
 * computes.
 *
 * <p>The CMAC is the one RFC 4493 gives, so a key's check value is the one
 * the standard's vectors give and a message of any length is authenticated
 * the way the standard says.
 */
final class PaymentCryptographyCrypto {

    /** The block size of AES, in bytes. */
    private static final int BLOCK_SIZE = 16;

    private static final byte[] ZERO_BLOCK = new byte[BLOCK_SIZE];

    /** The Rijndael constant the subkey derivation exclusive-oreds in when the shift carries out. */
    private static final int RB = 0x87;

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
        return toHex(Arrays.copyOf(mac, 3)).toUpperCase(Locale.ROOT);
    }

    /**
     * The AES-CMAC of a message under a key, as RFC 4493 gives it.
     *
     * <p>The message is divided into blocks and chained as CBC-MAC. A last
     * block of the block size is exclusive-ored with K1, and one shorter is
     * padded with a single one bit and exclusive-ored with K2, as the RFC
     * says.
     */
    static byte[] cmac(byte[] key, byte[] message) {
        try {
            byte[] k1 = shiftLeft(encrypt(ZERO_BLOCK, key));
            byte[] k2 = shiftLeft(k1);
            int blocks = (message.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
            boolean complete = message.length > 0 && message.length % BLOCK_SIZE == 0;
            if (blocks == 0) {
                blocks = 1;
            }
            byte[] last = new byte[BLOCK_SIZE];
            int offset = (blocks - 1) * BLOCK_SIZE;
            if (complete) {
                System.arraycopy(message, offset, last, 0, BLOCK_SIZE);
                last = xor(last, k1);
            } else {
                int remaining = message.length - offset;
                System.arraycopy(message, offset, last, 0, remaining);
                last[remaining] = (byte) 0x80;
                last = xor(last, k2);
            }
            byte[] chain = ZERO_BLOCK;
            for (int i = 0; i < blocks - 1; i++) {
                byte[] block = new byte[BLOCK_SIZE];
                System.arraycopy(message, i * BLOCK_SIZE, block, 0, BLOCK_SIZE);
                chain = encrypt(xor(chain, block), key);
            }
            return encrypt(xor(chain, last), key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * A block shifted left by one bit, with Rijndael's Rb exclusive-ored in
     * when the shift carried out of the top.
     *
     * <p>The bit that falls off the top of the block comes from the byte
     * above it, so the carry travels down the block.
     */
    private static byte[] shiftLeft(byte[] block) {
        byte[] shifted = new byte[BLOCK_SIZE];
        int carry = 0;
        for (int i = BLOCK_SIZE - 1; i >= 0; i--) {
            int nextCarry = (block[i] & 0x80) != 0 ? 1 : 0;
            shifted[i] = (byte) (((block[i] << 1) | carry) & 0xff);
            carry = nextCarry;
        }
        if ((block[0] & 0x80) != 0) {
            shifted[BLOCK_SIZE - 1] ^= (byte) RB;
        }
        return shifted;
    }

    private static byte[] xor(byte[] left, byte[] right) {
        byte[] out = new byte[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) (left[i] ^ right[i % right.length]);
        }
        return out;
    }

    /** One block of AES in ECB mode, the block cipher the CMAC is built on. */
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
