package io.floci.aws.services.paymentcryptography;


import io.floci.aws.services.paymentcryptography.crypto.Ciphers;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The cryptography a key record needs: its material, its check value, and the operations the
 * data plane performs with it.
 *
 * <p>The check value of an AES key is the leftmost three bytes of the CMAC of
 * sixteen zero bytes under the key. That is what the service computes for a
 * CMAC check value, and what the reference implementation its samples use
 * computes.
 *
 * <p>The data plane encrypts with AES or Triple DES in CBC mode and pads no data, so the
 * caller supplies whole blocks and the ciphertext is as long as the plaintext it holds, and
 * answers with the initialization vector and the ciphertext in one blob. The MAC is the CMAC of
 * the message under the key, cut to the length the caller asks for.
 */
final class PaymentCryptographyCrypto {

    private static final byte[] ZERO_BLOCK = new byte[16];

    /** The hexadecimal the key material is written in. */
    private static final HexFormat HEX = HexFormat.of();

    /** The hexadecimal a check value is written in: the capitals the reference writes it in. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    /** The block size of AES, in bytes. */
    private static final int BLOCK = 16;

    /** The initialization vector of a CBC encryption, in bytes. */
    private static final int IV_LENGTH = 16;

    /** The initialization vector of a Triple DES CBC encryption, in bytes. */
    private static final int TDES_IV_LENGTH = 8;

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
        return HEX_UPPER.formatHex(value);
    }

    /** The check value of a key of that algorithm, from the key's bytes. */
    static String checkValue(byte[] key, PaymentCryptographyKeyAlgorithm algorithm) {
        byte[] mac = algorithm.aes
                ? cmac(key, ZERO_BLOCK)
                : tdesEcbEncrypt(new byte[8], key);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return HEX_UPPER.formatHex(value);
    }

    /** The check value of an AES key: the leftmost three bytes of the CMAC of sixteen zero bytes. */
    static String checkValue(String keyHex) {
        byte[] mac = cmac(fromHex(keyHex), ZERO_BLOCK);
        byte[] value = new byte[3];
        System.arraycopy(mac, 0, value, 0, 3);
        return HEX_UPPER.formatHex(value);
    }

    /**
     * The Triple DES encryption of one block under a key, in ECB mode.
     *
     * <p>The check value of a Triple DES key is the leftmost three bytes of the encrypted
     * zeroes, so the block is encrypted and the first three bytes are taken.
     *
     * <p>BouncyCastle's cipher is used rather than the JCE's, whose Triple DES is not
     * registered in the native image: a request that needs it there hangs until the job
     * is cancelled at its timeout.
     *
     * @param block8 the eight-byte block to encrypt
     * @param key the key, sixteen or twenty-four bytes
     * @return the encrypted bytes
     */
    private static byte[] tdesEcbEncrypt(byte[] block8, byte[] key) {
        return Ciphers.tdes2Encrypt(block8, key);
    }

    /**
     * The AES-CMAC of a message under a key, as RFC 4493 computes it.
     *
     * <p>The message is split into blocks of sixteen bytes. The last block is the one that is
     * padded, unless the message fills it exactly, and it is exclusive-ored with the first
     * subkey when it is whole and with the second when it is padded.
     */
    static byte[] cmac(byte[] key, byte[] message) {
        byte[] k1 = subkeyOne(key);
        byte[] k2 = shiftLeft(k1);
        int blocks = Math.max(1, (message.length + BLOCK - 1) / BLOCK);
        byte[] last = new byte[BLOCK];
        int lastLength = message.length - (blocks - 1) * BLOCK;
        System.arraycopy(message, (blocks - 1) * BLOCK, last, 0, lastLength);
        if (lastLength == BLOCK) {
            last = Ciphers.xor(last, k1);
        } else {
            // The padding of a partial block is a one bit and zeroes, as the standard says.
            last[lastLength] = (byte) 0x80;
            last = Ciphers.xor(last, k2);
        }
        byte[] state = new byte[BLOCK];
        for (int i = 0; i < blocks - 1; i++) {
            byte[] block = new byte[BLOCK];
            System.arraycopy(message, i * BLOCK, block, 0, BLOCK);
            state = Ciphers.aesEcbEncrypt(Ciphers.xor(state, block), key);
        }
        return Ciphers.aesEcbEncrypt(Ciphers.xor(state, last), key);
    }

    /**
     * The first CMAC subkey.
     *
     * <p>It is the block cipher's output over a zero block, shifted left by one
     * bit, with Rijndael's Rb exclusive-ored in when the shift carried out of
     * the top.
     */
    private static byte[] subkeyOne(byte[] key) {
        return shiftLeft(Ciphers.aesEcbEncrypt(ZERO_BLOCK, key));
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
     * The ciphertext of a plaintext under a key, with the initialization vector the request
     * names.
     *
     * <p>A request that names a vector gets the ciphertext under it, so two parties that
     * agree on the vector can read each other's ciphertext. A request that names none gets a
     * zero vector, which is what the reference uses.
     *
     * @param key the key
     * @param plaintext the bytes to encrypt
     * @param initializationVector the vector to encrypt under, or null for the zero vector
     * @return the encrypted bytes
     */
    static byte[] encryptCbc(byte[] key, byte[] plaintext, byte[] initializationVector) {
        return aesCbcEncrypt(key, vector(initializationVector, IV_LENGTH), plaintext);
    }

    /**
     * The plaintext of a ciphertext the service encrypted.
     *
     * <p>The ciphertext is decrypted under the vector the request names. The cipher pads no
     * data, so a ciphertext that is not a whole number of blocks does not decrypt, and a
     * ciphertext that is not the key's does not decrypt either.
     *
     * @param key the key
     * @param ciphertext the encrypted bytes
     * @param initializationVector the vector to decrypt under, or null for the zero vector
     * @return the plaintext
     */
    static byte[] decryptCbc(byte[] key, byte[] ciphertext, byte[] initializationVector) {
        return aesCbcDecrypt(key, vector(initializationVector, IV_LENGTH), ciphertext);
    }

    /**
     * The ciphertext of a plaintext under a Triple DES key, with the initialization vector the
     * request names.
     *
     * <p>The cipher pads no data, so the caller supplies whole blocks of eight bytes, and the
     * ciphertext is as long as the plaintext it holds. A two-key key is used under the three keys
     * it holds, as the cipher takes them.
     *
     * @param key the key
     * @param plaintext the bytes to encrypt
     * @param initializationVector the vector to encrypt under, or null for the zero vector
     * @return the encrypted bytes
     */
    static byte[] tdesCbcEncrypt(byte[] key, byte[] plaintext, byte[] initializationVector) {
        return Ciphers.tdesCbcEncrypt(plaintext, key, vector(initializationVector, TDES_IV_LENGTH));
    }

    /**
     * The plaintext of a ciphertext the service encrypted under a Triple DES key.
     *
     * @param key the key
     * @param ciphertext the encrypted bytes
     * @param initializationVector the vector to decrypt under, or null for the zero vector
     * @return the plaintext
     */
    static byte[] tdesCbcDecrypt(byte[] key, byte[] ciphertext, byte[] initializationVector) {
        return Ciphers.tdesCbcDecrypt(ciphertext, key, vector(initializationVector, TDES_IV_LENGTH));
    }

    /** The vector a cipher starts from, the zero vector when the request names none. */
    private static byte[] vector(byte[] initializationVector, int length) {
        if (initializationVector == null) {
            return new byte[length];
        }
        if (initializationVector.length != length) {
            throw new IllegalArgumentException("the initialization vector is "
                    + initializationVector.length + " bytes; it is " + length);
        }
        return initializationVector.clone();
    }

    /**
     * The key wrap of RFC 3394: a plaintext wrapped under a key, with the check that says the
     * key was the right one.
     */
    static byte[] keyWrap(byte[] key, byte[] plaintext) {
        byte[] a = INITIAL_VALUE.clone();
        byte[][] r = split(plaintext, 8);
        int n = r.length;
        for (int j = 0; j < 6; j++) {
            for (int i = 0; i < n; i++) {
                byte[] block = concat(a, r[i]);
                byte[] encrypted = Ciphers.aesEcbEncrypt(block, key);
                a = Arrays.copyOfRange(encrypted, 0, 8);
                r[i] = Arrays.copyOfRange(encrypted, 8, encrypted.length);
                a = Ciphers.xor(a, counter(j, n, i));
            }
        }
        byte[] out = new byte[8 + plaintext.length];
        System.arraycopy(a, 0, out, 0, 8);
        for (int i = 0; i < n; i++) {
            System.arraycopy(r[i], 0, out, 8 + i * 8, 8);
        }
        return out;
    }

    /**
     * The plaintext of a key wrap, or null when the wrapping key was not the right one.
     */
    static byte[] keyUnwrap(byte[] key, byte[] wrapped) {
        if (wrapped.length < 16 || (wrapped.length - 8) % 8 != 0) {
            return null;
        }
        byte[] a = Arrays.copyOfRange(wrapped, 0, 8);
        byte[][] r = split(Arrays.copyOfRange(wrapped, 8, wrapped.length), 8);
        int n = r.length;
        for (int j = 5; j >= 0; j--) {
            for (int i = n - 1; i >= 0; i--) {
                byte[] block = concat(Ciphers.xor(a, counter(j, n, i)), r[i]);
                byte[] decrypted = Ciphers.aesEcbDecrypt(block, key);
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

    /**
     * The AES-CBC encryption of data under a key and an initialization vector, without
     * padding: the vector is the length of the data's block size already.
     */
    static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        return Ciphers.aesCbcEncrypt(data, key, iv);
    }

    /** The AES-CBC decryption of data under a key and an initialization vector, without padding. */
    static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            return Ciphers.aesCbcDecrypt(data, key, iv);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the data did not decrypt under the key", e);
        }
    }

    /** One block of AES in ECB mode. */
    static byte[] aesEcbEncrypt(byte[] input, byte[] key) {
        return Ciphers.aesEcbEncrypt(input, key);
    }

    static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }
}
