package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.DESEngine;
import org.bouncycastle.crypto.engines.DESedeEngine;
import org.bouncycastle.crypto.macs.CBCBlockCipherMac;
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;


/**
 * The block ciphers the data plane's MACs are built from.
 *
 * <p>The Triple DES and the Single DES are the two the ISO 9797-1 MACs and the DUKPT
 * derivations use, and both are used in ECB mode over a single block. BouncyCastle is used
 * for them rather than the JCE, whose provider defaults differ: its {@code DESede} takes the
 * two-key form K1||K2 as the three keys K1||K2||K1, so both the cipher and the key are the
 * ones the MAC is defined over.
 *
 * <p>The JCE's Triple DES is not registered in the native image, so a request that needs
 * it there hangs until the job is cancelled at its timeout, and the suite that would have
 * caught it never answers. Every cipher here is BouncyCastle's, so none of them depends
 * on what the image happens to carry.
 *
 * <p>ECB is the mode the block ciphers implement themselves, so one block goes in and one
 * block comes out, which is what the MACs read.
 */
public final class Ciphers {

    private Ciphers() {
    }

    /**
     * The AES encryption of data under a key and an initialization vector, in CBC mode, without
     * padding.
     *
     * <p>The cipher pads no data, so the caller supplies whole blocks: a partial block is
     * refused rather than padded.
     *
     * @param data the bytes to encrypt
     * @param key the sixteen-, twenty-four- or thirty-two-byte key
     * @param iv16 the sixteen-byte vector to encrypt under
     * @return the encrypted bytes
     */
    public static byte[] aesCbcEncrypt(byte[] data, byte[] key, byte[] iv16) {
        return blocks(AESEngine.newInstance(), true, data, key, iv16);
    }

    /**
     * The AES decryption of data under a key and an initialization vector, in CBC mode, without
     * padding.
     *
     * @param data the bytes to decrypt
     * @param key the sixteen-, twenty-four- or thirty-two-byte key
     * @param iv16 the sixteen-byte vector to decrypt under
     * @return the decrypted bytes
     */
    public static byte[] aesCbcDecrypt(byte[] data, byte[] key, byte[] iv16) {
        return blocks(AESEngine.newInstance(), false, data, key, iv16);
    }

    /**
     * One block of AES in ECB mode.
     *
     * @param block16 the sixteen-byte block to encrypt
     * @param key the sixteen-, twenty-four- or thirty-two-byte key
     * @return the encrypted bytes
     */
    public static byte[] aesEcbEncrypt(byte[] block16, byte[] key) {
        return block(AESEngine.newInstance(), true, block16, key);
    }

    /**
     * One block of AES in ECB mode, the other way round.
     *
     * @param block16 the sixteen-byte block to decrypt
     * @param key the sixteen-, twenty-four- or thirty-two-byte key
     * @return the decrypted bytes
     */
    public static byte[] aesEcbDecrypt(byte[] block16, byte[] key) {
        return block(AESEngine.newInstance(), false, block16, key);
    }

    /**
     * Widen a two-key (sixteen-byte) Triple DES key to the three-key form, K1||K2 to
     * K1||K2||K1.
     *
     * <p>The cipher takes the three keys K1||K2||K1, which is what the two-key form means,
     * and TR-31 derives a type B block's keys from the widened form, so the derivation and
     * the cipher have to be on the same key. A three-key key is returned as it is.
     *
     * @param key the sixteen- or twenty-four-byte key
     * @return the twenty-four-byte key
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

    /**
     * The Triple DES encryption of a block under a two-key key, in ECB mode.
     *
     * <p>The two-key form is K1||K2, and the cipher takes the three keys K1||K2||K1, which
     * is what the two-key form means. A three-key key is taken as it is.
     *
     * @param block8 the eight-byte block to encrypt
     * @param key the sixteen- or twenty-four-byte key
     * @return the encrypted block
     */
    public static byte[] tdes2Encrypt(byte[] block8, byte[] key) {
        return tdes3Encrypt(block8, key);
    }

    /**
     * The Triple DES encryption of a block under a three-key key, in ECB mode.
     *
     * <p>The two-key form is K1||K2, and the cipher takes the three keys K1||K2||K1, which
     * is what the two-key form means. A three-key key is taken as it is.
     *
     * @param block8 the eight-byte block to encrypt
     * @param key the sixteen- or twenty-four-byte key
     * @return the encrypted block
     */
    public static byte[] tdes3Encrypt(byte[] block8, byte[] key) {
        return block(new DESedeEngine(), true, block8, key);
    }

    /**
     * The Triple DES decryption of a block under a key, in ECB mode.
     *
     * <p>The two-key form is K1||K2, and the cipher takes the three keys K1||K2||K1, which
     * is what the two-key form means. A three-key key is taken as it is.
     *
     * @param block8 the eight-byte block to decrypt
     * @param key the sixteen- or twenty-four-byte key
     * @return the decrypted block
     */
    public static byte[] tdesEcbDecrypt(byte[] block8, byte[] key) {
        return block(new DESedeEngine(), false, block8, key);
    }

    /**
     * The Single DES encryption of a block under a key, in ECB mode.
     *
     * @param block8 the eight-byte block to encrypt
     * @param key8 the eight-byte key
     * @return the encrypted block
     */
    public static byte[] tdesSingleDesEncrypt(byte[] block8, byte[] key8) {
        return block(new DESEngine(), true, block8, key8);
    }

    /**
     * The Single DES decryption of a block under a key, in ECB mode.
     *
     * @param block8 the eight-byte block to decrypt
     * @param key8 the eight-byte key
     * @return the decrypted block
     */
    public static byte[] tdesSingleDesDecrypt(byte[] block8, byte[] key8) {
        return block(new DESEngine(), false, block8, key8);
    }

    /**
     * The Triple DES encryption of data under a two-key key, in CBC mode.
     *
     * <p>The two-key form is K1||K2, and the cipher takes three keys, so it is widened to
     * K1||K2||K1, which is what the two-key form means. A three-key key is taken as it is.
     * The cipher pads no data, so the data fills whole blocks of eight bytes.
     *
     * @param data the data to encrypt, a whole number of blocks
     * @param key the sixteen- or twenty-four-byte key
     * @param iv8 the eight-byte vector to encrypt under
     * @return the encrypted bytes
     */
    public static byte[] tdesCbcEncrypt(byte[] data, byte[] key, byte[] iv8) {
        return blocks(new DESedeEngine(), true, data, key, iv8);
    }

    /**
     * The Triple DES decryption of data under a two-key key, in CBC mode.
     *
     * @param data the encrypted bytes, a whole number of blocks
     * @param key the sixteen- or twenty-four-byte key
     * @param iv8 the eight-byte vector to decrypt under
     * @return the plaintext
     */
    public static byte[] tdesCbcDecrypt(byte[] data, byte[] key, byte[] iv8) {
        return blocks(new DESedeEngine(), false, data, key, iv8);
    }

    /**
     * The exclusive-or of two byte arrays of the same length.
     *
     * @param a the first bytes
     * @param b the second bytes
     * @return the exclusive-or of the two
     */
    public static byte[] xor(byte[] a, byte[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both byte arrays are required");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("the byte arrays are of different lengths: "
                    + a.length + " and " + b.length);
        }
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /**
     * The ISO 9797-1 MAC algorithm 1 over Triple DES, with padding method 1.
     *
     * <p>Algorithm 1 is the CBC mode's chain over the zero-padded message, so the last
     * block it leaves is the MAC.
     *
     * <p>Padding method 1 pads the message with zeroes only when it does not already fill
     * its blocks: a message that fills them is chained as it is, and no block is added.
     *
     * @param key16 the sixteen-byte key
     * @param data the message to authenticate
     * @return the eight-byte MAC
     */
    public static byte[] iso9797Alg1(byte[] key16, byte[] data) {
        // The MAC without a padding of its own pads the last block with zeroes, which is
        // padding method 1. A padder that fills a whole block would chain an empty one
        // after a message that already fills its blocks, which the standard does not do.
        CBCBlockCipherMac mac = new CBCBlockCipherMac(new DESedeEngine(), 64);
        mac.init(new ParametersWithIV(new KeyParameter(key16), new byte[8]));
        mac.update(data, 0, data.length);
        byte[] out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);
        return out;
    }

    /**
     * The ISO 9797-1 MAC algorithm 3, the retail MAC, over a double-length DES key,
     * with padding method 1.
     *
     * <p>Algorithm 3 is the retail MAC: a single-DES chain under K1 for all but the
     * last block, then a final EDE under K1||K2.
     *
     * @param key16 the sixteen-byte key, K1||K2
     * @param data the message to authenticate
     * @return the eight-byte MAC
     */
    public static byte[] iso9797Alg3(byte[] key16, byte[] data) {
        ISO9797Alg3Mac mac = new ISO9797Alg3Mac(new DESEngine());
        mac.init(new KeyParameter(key16));
        mac.update(data, 0, data.length);
        byte[] out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);
        return out;
    }

    /**
     * The encryption or decryption of one block under a key, in the cipher's own ECB mode.
     */
    private static byte[] block(BlockCipher cipher, boolean encrypt, byte[] block, byte[] key) {
        int size = cipher.getBlockSize();
        if (block == null || block.length != size) {
            throw new IllegalArgumentException("the block is "
                    + (block == null ? "missing" : block.length + " bytes") + "; it is " + size);
        }
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        cipher.init(encrypt, new KeyParameter(key));
        byte[] out = new byte[size];
        cipher.processBlock(block, 0, out, 0);
        return out;
    }

    /**
     * The encryption or decryption of whole blocks under a key, in CBC mode.
     */
    private static byte[] blocks(BlockCipher cipher, boolean encrypt, byte[] data, byte[] key, byte[] iv) {
        int size = cipher.getBlockSize();
        if (data == null || data.length % size != 0) {
            throw new IllegalArgumentException("the data is "
                    + (data == null ? "missing" : data.length + " bytes")
                    + "; it is a whole number of " + size + "-byte blocks");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (iv == null || iv.length != size) {
            throw new IllegalArgumentException("the initialization vector is "
                    + (iv == null ? "missing" : iv.length + " bytes") + "; it is " + size);
        }
        BlockCipher cbc = CBCBlockCipher.newInstance(cipher);
        cbc.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        byte[] out = new byte[data.length];
        for (int offset = 0; offset < data.length; offset += size) {
            cbc.processBlock(data, offset, out, offset);
        }
        return out;
    }
}
