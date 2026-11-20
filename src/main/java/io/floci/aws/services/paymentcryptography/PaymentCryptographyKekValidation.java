package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.Ciphers;
import io.floci.aws.services.paymentcryptography.crypto.EmvCrypto;

import java.util.Arrays;

/**
 * The AS2805 KEK validation: the ceremony two nodes use to prove they hold the same key.
 *
 * <p>The node that starts the exchange enciphers a random number under a variant of the key
 * it holds and sends it. The node it talks to deciphers that number, inverts every bit of
 * it, and enciphers the inverted value under another variant of its own key. The first node
 * compares the answer with the one it computed, and the two agree only when both hold the
 * same key.
 *
 * <p>A variant is the key exclusive-ored with a constant, tiled over the key's length: the
 * constant says what the variant is for, so a key made for one purpose does not open under
 * the variant of another. The reference names the constant of the random number's variant
 * 82 and that of the inverted number's 84, and the longer form of the first 82C0.
 *
 * <p>The random number is eight, sixteen or twenty-four bytes, and every byte of it carries
 * the odd parity the standard's Single DES asks for. It is enciphered a block at a time
 * under the variant, in the cipher the key's own algorithm names.
 */
final class PaymentCryptographyKekValidation {

    /** The block of the cipher the ceremony runs in: the Triple DES block of eight bytes. */
    private static final int BLOCK = 8;

    /**
     * The variant masks a request may name, and the mask of the inverted number's variant.
     */
    enum Variant {

        /** The random number is enciphered under the 82 variant, the inverted one under 84. */
        VARIANT_MASK_82("82", "84"),

        /** The random number is enciphered under the 82C0 variant, the inverted one under 84C0. */
        VARIANT_MASK_82C0("82C0", "84C0");

        private final String sendMask;
        private final String receiveMask;

        Variant(String sendMask, String receiveMask) {
            this.sendMask = sendMask;
            this.receiveMask = receiveMask;
        }

        /** The mask of the random number's variant. */
        String sendMask() {
            return sendMask;
        }

        /** The mask of the inverted number's variant. */
        String receiveMask() {
            return receiveMask;
        }

        /**
         * The variant a request names.
         *
         * <p>The request's mask is one of the model's two, and the shape check settles it
         * before the operation reads it, so a name that is not one of them is a fault of the
         * service rather than of the caller.
         *
         * @param name the name of the mask
         * @return the variant
         */
        static Variant from(String name) {
            for (Variant variant : values()) {
                if (variant.name().equals(name)) {
                    return variant;
                }
            }
            throw new IllegalStateException("the variant mask " + name + " is not one of them");
        }
    }

    private PaymentCryptographyKekValidation() {
    }

    /**
     * The variant of a key: the key exclusive-ored with the mask, tiled over its length.
     *
     * <p>The mask is a constant the standard gives for the purpose, and it is repeated over
     * the whole key, so a key of any length gets a variant of the same length.
     *
     * @param key the key the variant is of
     * @param mask the mask, as hexadecimal
     * @return the variant key
     */
    static byte[] variantKey(byte[] key, String mask) {
        byte[] constant = PaymentCryptographyCrypto.fromHex(mask);
        byte[] out = new byte[key.length];
        for (int i = 0; i < key.length; i++) {
            out[i] = (byte) (key[i] ^ constant[i % constant.length]);
        }
        return out;
    }

    /**
     * The odd parity every byte of the random number carries.
     *
     * <p>The standard's Single DES reads a key whose bytes each hold an odd number of bits,
     * so the random number is put to that parity before it is enciphered. The rule is the
     * service's own DES parity rule, so there is one of it: a byte whose lowest bit is
     * already set and whose count is even is corrected, not left as it is.
     *
     * @param data the random number
     * @return the random number, with the parity put to it
     */
    static byte[] oddParity(byte[] data) {
        return EmvCrypto.adjustDesParity(data);
    }

    /**
     * The inversion of every byte: the number the node answers with.
     *
     * @param data the number to invert
     * @return the inverted number
     */
    static byte[] invert(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) ~data[i];
        }
        return out;
    }

    /**
     * The enciphering of the number under a variant, a block at a time.
     *
     * <p>The cipher is the one the key's algorithm names, and it is the cipher's own ECB
     * mode: every block stands alone, so the number is enciphered without a vector and
     * without a chain.
     *
     * @param data the number to encipher
     * @param variantKey the variant key
     * @param algorithm the algorithm of the key
     * @return the enciphered number
     */
    static byte[] encrypt(byte[] data, byte[] variantKey,
                          PaymentCryptographyKeyAlgorithm algorithm) {
        return blocks(data, variantKey, algorithm, true);
    }

    /**
     * The deciphering of the number under a variant, a block at a time.
     *
     * @param data the enciphered number
     * @param variantKey the variant key
     * @param algorithm the algorithm of the key
     * @return the number
     */
    static byte[] decrypt(byte[] data, byte[] variantKey,
                          PaymentCryptographyKeyAlgorithm algorithm) {
        return blocks(data, variantKey, algorithm, false);
    }

    /**
     * The cipher's own mode over whole blocks.
     *
     * <p>The block is the Triple DES block of eight bytes, and the lengths the model names
     * for the random number are whole numbers of them, so the refusal is a guard on the
     * cipher rather than a refusal the model settles.
     */
    private static byte[] blocks(byte[] data, byte[] variantKey,
                                 PaymentCryptographyKeyAlgorithm algorithm, boolean encrypt) {
        if (data.length % BLOCK != 0) {
            throw new AwsException("ValidationException",
                    "the number is " + data.length + " bytes; it is a whole number of "
                            + BLOCK + "-byte blocks", 400);
        }
        byte[] out = new byte[data.length];
        for (int offset = 0; offset < data.length; offset += BLOCK) {
            byte[] block = Arrays.copyOfRange(data, offset, offset + BLOCK);
            byte[] ciphered = encrypt
                    ? encryptBlock(block, variantKey, algorithm)
                    : decryptBlock(block, variantKey);
            System.arraycopy(ciphered, 0, out, offset, BLOCK);
        }
        return out;
    }

    /** One block enciphered. */
    private static byte[] encryptBlock(byte[] block, byte[] key,
                                       PaymentCryptographyKeyAlgorithm algorithm) {
        return PaymentCryptographyKeyAlgorithm.TDES_2KEY.equals(algorithm)
                ? Ciphers.tdes2Encrypt(block, key)
                : Ciphers.tdes3Encrypt(block, key);
    }

    /** One block deciphered. */
    private static byte[] decryptBlock(byte[] block, byte[] key) {
        return Ciphers.tdesEcbDecrypt(block, key);
    }
}
