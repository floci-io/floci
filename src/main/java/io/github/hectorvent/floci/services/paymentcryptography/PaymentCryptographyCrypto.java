package io.github.hectorvent.floci.services.paymentcryptography;


import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * The cryptography a key record needs: its material, its check value, and the operations the
 * data plane performs with it.
 *
 * <p>The check value of an AES key is the leftmost three bytes of the CMAC of
 * sixteen zero bytes under the key. That is what the service computes for a
 * CMAC check value, and what the reference implementation its samples use
 * computes.
 *
 * <p>The data plane encrypts with AES in CBC mode and the padding the service calls PKCS#5,
 * which is PKCS#7 for a block cipher, and answers with the initialization vector and the
 * ciphertext in one blob. The MAC is the CMAC of the message under the key, cut to the
 * length the caller asks for.
 */
final class PaymentCryptographyCrypto {

    private static final byte[] ZERO_BLOCK = new byte[16];

    /** The block size of AES, in bytes. */
    private static final int BLOCK = 16;

    /** The initialization vector of a CBC encryption, in bytes. */
    private static final int IV_LENGTH = 16;

    /** The initial value of a key wrap, which the standard gives as it. */
    private static final byte[] INITIAL_VALUE = {
            (byte) 0xa6, (byte) 0xa6, (byte) 0xa6, (byte) 0xa6,
            (byte) 0xa6, (byte) 0xa6, (byte) 0xa6, (byte) 0xa6};

    private PaymentCryptographyCrypto() {
    }

    /** Fresh material of the algorithm's length, as hexadecimal. */
    static String randomMaterialHex(PaymentCryptographyKeyAlgorithm algorithm, SecureRandom random) {
        byte[] material = new byte[algorithm.bytes];
        random.nextBytes(material);
        return toHex(material);
    }

    /** Fresh AES-256 material, as hexadecimal. */
    static String randomAes256Hex(SecureRandom random) {
        return randomMaterialHex(PaymentCryptographyKeyAlgorithm.AES_256, random);
    }

    /**
     * The check value of a key of that algorithm.
     *
     * <p>A TDES key's value is the ANSI X9.24 one: the leftmost three bytes of encrypting
     * eight zero bytes with the key. An AES key's value is the leftmost three bytes of the
     * CMAC of sixteen zero bytes under it.
     */
    static String checkValue(String keyHex, PaymentCryptographyKeyAlgorithm algorithm) {
        byte[] key = fromHex(keyHex);
        byte[] mac = algorithm.aes
                ? cmac(key, ZERO_BLOCK)
                : tdesEcbEncrypt(new byte[8], key);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return toHex(value).toUpperCase(Locale.ROOT);
    }

    /** The check value of a key of that algorithm, from the key's bytes. */
    static String checkValue(byte[] key, PaymentCryptographyKeyAlgorithm algorithm) {
        byte[] mac = algorithm.aes
                ? cmac(key, ZERO_BLOCK)
                : tdesEcbEncrypt(new byte[8], key);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return toHex(value).toUpperCase(Locale.ROOT);
    }

    /** The check value of an AES key: the leftmost three bytes of the CMAC of sixteen zero bytes. */
    static String checkValue(String keyHex) {
        byte[] mac = cmac(fromHex(keyHex), ZERO_BLOCK);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return toHex(value).toUpperCase(Locale.ROOT);
    }

    /**
     * The Triple DES encryption of a block under a key, in ECB mode.
     *
     * <p>The check value of a Triple DES key is the leftmost three bytes of the encrypted
     * zeroes, so the block is encrypted and the first three bytes are taken.
     *
     * @param data the bytes to encrypt
     * @param key the key, sixteen or twenty-four bytes
     * @return the encrypted bytes
     */
    private static byte[] tdesEcbEncrypt(byte[] data, byte[] key) {
        try {
            byte[] key24 = key.length == 24 ? key : widenTdesKey(key);
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key24, "DESede"));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the Triple DES key could not be used", e);
        }
    }

    /** A two-key Triple DES key widened to the three the cipher takes. */
    private static byte[] widenTdesKey(byte[] key) {
        byte[] wide = new byte[24];
        System.arraycopy(key, 0, wide, 0, 16);
        System.arraycopy(key, 0, wide, 16, 8);
        return wide;
    }

    /**
     * The AES-CMAC of a message under a key, as RFC 4493 computes it.
     *
     * <p>The message is split into blocks of sixteen bytes. The last block is the one that is
     * padded, unless the message fills it exactly, and it is exclusive-ored with the first
     * subkey when it is whole and with the second when it is padded.
     */
    static byte[] cmac(byte[] key, byte[] message) {
        try {
            byte[] k1 = subkeyOne(key);
            byte[] k2 = shiftLeft(k1);
            int blocks = Math.max(1, (message.length + BLOCK - 1) / BLOCK);
            byte[] last = new byte[BLOCK];
            int lastLength = message.length - (blocks - 1) * BLOCK;
            System.arraycopy(message, (blocks - 1) * BLOCK, last, 0, lastLength);
            if (lastLength == BLOCK) {
                last = xor(last, k1);
            } else {
                // The padding of a partial block is a one bit and zeroes, as the standard says.
                last[lastLength] = (byte) 0x80;
                last = xor(last, k2);
            }
            byte[] state = new byte[BLOCK];
            for (int i = 0; i < blocks - 1; i++) {
                byte[] block = new byte[BLOCK];
                System.arraycopy(message, i * BLOCK, block, 0, BLOCK);
                state = encrypt(xor(state, block), key);
            }
            return encrypt(xor(state, last), key);
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
            return shiftLeft(encrypt(ZERO_BLOCK, key));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * The two CMAC subkeys of a key: the first and the second.
     *
     * <p>The key derivation of a TR-31 block is seeded with one of them, so they are
     * wanted on their own.
     */
    static byte[][] cmacSubkeys(byte[] key) {
        byte[] k1 = subkeyOne(key);
        return new byte[][] {k1, shiftLeft(k1)};
    }

    /**
     * A block shifted left by one bit, with Rijndael's Rb exclusive-ored in when the shift
     * carried out of the top.
     */
    private static byte[] shiftLeft(byte[] block) {
        byte[] out = new byte[BLOCK];
        int carry = 0;
        for (int i = BLOCK - 1; i >= 0; i--) {
            // The bit that falls off the top of the block comes from the
            // byte above it, so the carry travels down the block.
            int nextCarry = (block[i] & 0x80) != 0 ? 1 : 0;
            out[i] = (byte) (((block[i] << 1) | carry) & 0xff);
            carry = nextCarry;
        }
        if ((block[0] & 0x80) != 0) {
            out[BLOCK - 1] ^= (byte) 0x87;
        }
        return out;
    }

    /**
     * The ciphertext of a plaintext under a key: the initialization vector and the encrypted
     * bytes in one blob, which is how the service answers.
     */
    static byte[] encryptCbc(byte[] key, byte[] plaintext, SecureRandom random) {
        return encryptCbc(key, plaintext, null, random);
    }

    /**
     * The ciphertext of a plaintext under a key, with the initialization vector the request
     * names.
     *
     * <p>A request that names a vector gets the ciphertext under it, so two parties that
     * agree on the vector can read each other's ciphertext. A request that names none gets a
     * fresh one.
     *
     * @param key the key
     * @param plaintext the bytes to encrypt
     * @param initializationVector the vector to encrypt under, or null for a fresh one
     * @param random the randomness a fresh vector comes from
     * @return the vector and the encrypted bytes in one blob
     */
    static byte[] encryptCbc(byte[] key, byte[] plaintext, byte[] initializationVector,
                             SecureRandom random) {
        try {
            byte[] iv;
            if (initializationVector == null) {
                iv = new byte[IV_LENGTH];
                random.nextBytes(iv);
            } else {
                if (initializationVector.length != IV_LENGTH) {
                    throw new IllegalArgumentException("the initialization vector is "
                            + initializationVector.length + " bytes; it is " + IV_LENGTH);
                }
                iv = initializationVector.clone();
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * The plaintext of a blob the service encrypted.
     *
     * <p>The blob is the initialization vector and the ciphertext, so the vector is read first
     * and the rest is decrypted under it. A blob that is not the key's does not decrypt, and
     * the padding does not hold.
     */
    static byte[] decryptCbc(byte[] key, byte[] blob) {
        if (blob.length <= IV_LENGTH) {
            throw new IllegalArgumentException("the ciphertext is shorter than the initialization vector");
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(blob, IV_LENGTH, blob.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the ciphertext did not open under the key", e);
        }
    }

    /**
     * The key wrap of RFC 3394: a plaintext wrapped under a key, with the check that says the
     * key was the right one.
     */
    static byte[] keyWrap(byte[] key, byte[] plaintext) {
        try {
            byte[] a = INITIAL_VALUE.clone();
            byte[][] r = split(plaintext, 8);
            int n = r.length;
            for (int j = 0; j < 6; j++) {
                for (int i = 0; i < n; i++) {
                    byte[] block = concat(a, r[i]);
                    byte[] encrypted = encrypt(block, key);
                    a = Arrays.copyOfRange(encrypted, 0, 8);
                    r[i] = Arrays.copyOfRange(encrypted, 8, encrypted.length);
                    a = xor(a, counter(j, n, i));
                }
            }
            byte[] out = new byte[8 + plaintext.length];
            System.arraycopy(a, 0, out, 0, 8);
            for (int i = 0; i < n; i++) {
                System.arraycopy(r[i], 0, out, 8 + i * 8, 8);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * The plaintext of a key wrap, or null when the wrapping key was not the right one.
     */
    static byte[] keyUnwrap(byte[] key, byte[] wrapped) {
        if (wrapped.length < 16 || (wrapped.length - 8) % 8 != 0) {
            return null;
        }
        try {
            byte[] a = Arrays.copyOfRange(wrapped, 0, 8);
            byte[][] r = split(Arrays.copyOfRange(wrapped, 8, wrapped.length), 8);
            int n = r.length;
            for (int j = 5; j >= 0; j--) {
                for (int i = n - 1; i >= 0; i--) {
                    byte[] block = concat(xor(a, counter(j, n, i)), r[i]);
                    byte[] decrypted = decrypt(block, key);
                    a = Arrays.copyOfRange(decrypted, 0, 8);
                    r[i] = Arrays.copyOfRange(decrypted, 8, decrypted.length);
                }
            }
            byte[] check = INITIAL_VALUE;
            byte[] out = new byte[wrapped.length - 8];
            for (int i = 0; i < n; i++) {
                System.arraycopy(r[i], 0, out, i * 8, 8);
            }
            return Arrays.equals(a, check) ? out : null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /**
     * The wrap counter of the block at {@code index} of a wrap of {@code n} blocks: the
     * standard's {@code t}, which is the round times the number of blocks and the block.
     */
    private static byte[] counter(int round, int n, int index) {
        byte[] value = new byte[8];
        value[7] = (byte) (round * n + index + 1);
        return value;
    }

    private static byte[][] split(byte[] bytes, int size) {
        int count = bytes.length / size;
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            out[i] = Arrays.copyOfRange(bytes, i * size, (i + 1) * size);
        }
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static byte[] xor(byte[] left, byte[] right) {
        byte[] out = new byte[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) (left[i] ^ right[i % right.length]);
        }
        return out;
    }

    /**
     * The AES-CBC encryption of data under a key and an initialization vector, without
     * padding: the vector is the length of the data's block size already.
     */
    static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /** The AES-CBC decryption of data under a key and an initialization vector, without padding. */
    static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the data did not decrypt under the key", e);
        }
    }

    /** One block of AES in ECB mode. */
    static byte[] aesEcbEncrypt(byte[] input, byte[] key) {
        try {
            return encrypt(input, key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AES key could not be used", e);
        }
    }

    /** One block of AES in ECB mode. */
    private static byte[] encrypt(byte[] input, byte[] key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(input);
    }

    /** One block of AES in ECB mode, the other way round. */
    private static byte[] decrypt(byte[] input, byte[] key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
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
