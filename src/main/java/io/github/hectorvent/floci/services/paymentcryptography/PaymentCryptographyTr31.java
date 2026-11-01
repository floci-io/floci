package io.github.hectorvent.floci.services.paymentcryptography;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The TR-31 key block, the form a key takes when it leaves the service.
 *
 * <p>A type D block carries a key under a key block protection key: the key is length
 * encoded, encrypted under the derived key, and the block carries a MAC over the header and
 * the length encoded key. The keys the service makes are AES, so the block is a type D one and
 * the protection key is an AES key too.
 *
 * <p>The length field of the header counts the whole block in characters, and the key is
 * length encoded with its length in bits in front of it, so a reader knows how long the key is
 * without the confidential data saying.
 */
final class PaymentCryptographyTr31 {

    /** The initialization vector block of the type D key derivation. */
    private static final String AES_IV_CONST = "80000000000000000000000000000000";

    /** The length of the header, in characters. */
    private static final int HEADER_LENGTH = 16;

    /** The length of the MAC of a type D block, in characters. */
    private static final int MAC_LENGTH = 32;

    private PaymentCryptographyTr31() {
    }

    /**
     * Wraps a key into a TR-31 block under a key block protection key.
     *
     * @param clearKey      the key the block carries
     * @param protectionKey the key that protects the block
     * @param usage         the usage code of the key, such as {@code D0}
     * @param mode          the mode of use code of the key, such as {@code B}
     * @param exportability {@code E} when the key may leave the service again
     * @return the block, in upper-case hexadecimal
     */
    static String wrap(byte[] clearKey, byte[] protectionKey, String usage, String mode,
                       String exportability) {
        byte[] lengthEncoded = lengthEncode(clearKey);
        int blockLength = HEADER_LENGTH + lengthEncoded.length * 2 + MAC_LENGTH;
        String header = header16(usage, mode, exportability, blockLength);

        byte[][] derived = deriveKeys(protectionKey);
        byte[] encryptionKey = derived[0];
        byte[] macKey = derived[1];
        byte[] mask = PaymentCryptographyCrypto.cmacSubkeys(macKey)[0];
        byte[] mac = computeMac(header, lengthEncoded, macKey, mask);

        // The block is encrypted under the derived key, and the MAC is the vector of that
        // encryption: the block is authenticated before it is read.
        byte[] cipherText = PaymentCryptographyCrypto.aesCbcEncrypt(encryptionKey, mac, lengthEncoded);
        return header + hexUpper(cipherText) + hexUpper(mac);
    }

    /**
     * Opens a TR-31 block under a key block protection key, and gives the key it carries.
     *
     * @param block         the block, in hexadecimal
     * @param protectionKey the key that protects the block
     * @return the key the block carries
     * @throws IllegalArgumentException when the block does not authenticate, or its header says
     *                                  something this service does not serve
     */
    static byte[] unwrap(String block, byte[] protectionKey) {
        if (block == null || block.length() < HEADER_LENGTH + MAC_LENGTH) {
            throw new IllegalArgumentException("the key block is too short to be a key block");
        }
        String header = block.substring(0, HEADER_LENGTH);
        if (!"D".equals(header.substring(0, 1))) {
            throw new IllegalArgumentException("this service makes type D key blocks, and the block is type "
                    + header.substring(0, 1));
        }
        int declared = parseInt(header.substring(1, 5), "the length of the key block");
        if (declared != block.length()) {
            throw new IllegalArgumentException("the key block says it is " + declared
                    + " characters long, and it is " + block.length());
        }
        byte[] mac = fromHex(block.substring(block.length() - MAC_LENGTH));
        byte[] cipherText = fromHex(block.substring(HEADER_LENGTH, block.length() - MAC_LENGTH));

        byte[][] derived = deriveKeys(protectionKey);
        byte[] encryptionKey = derived[0];
        byte[] macKey = derived[1];
        byte[] mask = PaymentCryptographyCrypto.cmacSubkeys(macKey)[0];
        byte[] lengthEncoded = PaymentCryptographyCrypto.aesCbcDecrypt(encryptionKey, mac, cipherText);

        byte[] recomputed = computeMac(header, lengthEncoded, macKey, mask);
        // The MACs are compared as bytes and in constant time: a comparison that stopped at
        // the first difference would say how much of the MAC matched.
        if (!MessageDigest.isEqual(recomputed, mac)) {
            throw new IllegalArgumentException("the key block did not authenticate under the key");
        }

        int bits = parseInt(hexUpper(Arrays.copyOfRange(lengthEncoded, 0, 2)), "the length of the key", 16);
        if (lengthEncoded.length < 2 + bits / 8) {
            throw new IllegalArgumentException("the key block says its key is longer than the block");
        }
        return Arrays.copyOfRange(lengthEncoded, 2, 2 + bits / 8);
    }

    /** The usage code of a block's header, such as {@code D0}. */
    static String usageOf(String block) {
        return block.substring(5, 7);
    }

    /** The mode of use code of a block's header, such as {@code B}. */
    static String modeOf(String block) {
        return block.substring(8, 9);
    }

    /** The exportability of a block's header: {@code E}, {@code N} or {@code S}. */
    static String exportabilityOf(String block) {
        return block.substring(11, 12);
    }

    /**
     * The 16-character header of a block, whose layout the standard fixes.
     *
     * @param blockLength the length of the whole block, in characters
     */
    private static String header16(String usage, String mode, String exportability, int blockLength) {
        String header = "D"
                + pad4(blockLength)
                + usage
                + "A"     // the algorithm of the key the block carries: AES
                + mode
                + "00"    // the version of the key
                + exportability
                + "00"    // the number of optional blocks
                + "00";   // the reserved characters
        if (header.length() != HEADER_LENGTH) {
            throw new IllegalArgumentException("the key block header must be 16 characters");
        }
        return header;
    }

    /**
     * The key as a length encoded block: its length in bits in front of it, zero padded to the
     * block size.
     */
    private static byte[] lengthEncode(byte[] clearKey) {
        byte[] body = new byte[2 + clearKey.length];
        body[0] = (byte) ((clearKey.length * 8) >> 8);
        body[1] = (byte) (clearKey.length * 8);
        System.arraycopy(clearKey, 0, body, 2, clearKey.length);
        int padding = (BLOCK_SIZE - (body.length % BLOCK_SIZE)) % BLOCK_SIZE;
        byte[] out = new byte[body.length + padding];
        System.arraycopy(body, 0, out, 0, body.length);
        return out;
    }

    /** The block size of AES, in bytes. */
    private static final int BLOCK_SIZE = 16;

    /**
     * The MAC of a block: the CBC-MAC of the header and the length encoded key, with the last
     * block masked by a subkey of the MAC key.
     */
    private static byte[] computeMac(String header, byte[] lengthEncoded, byte[] macKey, byte[] mask) {
        byte[] data = new byte[header.length() + lengthEncoded.length];
        System.arraycopy(header.getBytes(StandardCharsets.US_ASCII), 0, data, 0, header.length());
        System.arraycopy(lengthEncoded, 0, data, header.length(), lengthEncoded.length);

        byte[] last;
        if (data.length % BLOCK_SIZE != 0) {
            int padding = BLOCK_SIZE - (data.length % BLOCK_SIZE);
            data = Arrays.copyOf(data, data.length + padding);
            data[data.length - padding] = (byte) 0x80;
            last = xor(Arrays.copyOfRange(data, data.length - BLOCK_SIZE, data.length), mask);
        } else {
            last = xor(Arrays.copyOfRange(data, data.length - BLOCK_SIZE, data.length), mask);
        }
        byte[] macInput = Arrays.copyOf(data, data.length);
        System.arraycopy(last, 0, macInput, macInput.length - BLOCK_SIZE, BLOCK_SIZE);
        byte[] out = PaymentCryptographyCrypto.aesCbcEncrypt(macKey, new byte[BLOCK_SIZE], macInput);
        return Arrays.copyOfRange(out, out.length - BLOCK_SIZE, out.length);
    }

    /**
     * The two keys a block is made under: the one that encrypts the key and the one that
     * authenticates it.
     *
     * <p>The derivation is the counter mode key derivation of NIST SP800-108, seeded with the
     * second CMAC subkey of the protection key and encrypted by the protection key itself.
     */
    private static byte[][] deriveKeys(byte[] protectionKey) {
        int bits = protectionKey.length * 8;
        int derivedBytes = bits / 8;
        int firstPart = 16;
        int secondPart = derivedBytes - firstPart;
        byte[] seed = PaymentCryptographyCrypto.cmacSubkeys(protectionKey)[1];

        byte[] encryptionKey = new byte[derivedBytes];
        byte[] macKey = new byte[derivedBytes];
        System.arraycopy(part(seed, bits, false, 1, protectionKey), 0, encryptionKey, 0, firstPart);
        System.arraycopy(part(seed, bits, true, 1, protectionKey), 0, macKey, 0, firstPart);
        if (secondPart > 0) {
            System.arraycopy(part(seed, bits, false, 2, protectionKey), 0, encryptionKey, firstPart, secondPart);
            System.arraycopy(part(seed, bits, true, 2, protectionKey), 0, macKey, firstPart, secondPart);
        }
        return new byte[][] {encryptionKey, macKey};
    }

    /** One part of the derivation: the protection key encrypts the seeded constant. */
    private static byte[] part(byte[] seed, int bits, boolean mac, int counter, byte[] protectionKey) {
        byte[] constant = fromHex(derivationConstant(bits, mac, counter));
        byte[] input = xor(seed, constant);
        return PaymentCryptographyCrypto.aesEcbEncrypt(input, protectionKey);
    }

    /** The constant of the key derivation, which says what is derived and how long it is. */
    private static String derivationConstant(int bits, boolean mac, int counter) {
        String algorithm = bits == 128 ? "0002" : bits == 192 ? "0003" : "0004";
        String use = mac ? "0001" : "0000";
        String keyLength = bits == 128 ? "0080" : bits == 192 ? "00C0" : "0100";
        return (counter < 10 ? "0" + counter : Integer.toString(counter))
                + use + "00" + algorithm + keyLength + AES_IV_CONST;
    }

    private static byte[] xor(byte[] left, byte[] right) {
        byte[] out = new byte[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) (left[i] ^ right[i % right.length]);
        }
        return out;
    }

    /** The length of the block, in four decimal characters. */
    private static String pad4(int value) {
        String text = Integer.toString(value);
        if (text.length() > 4) {
            throw new IllegalArgumentException("the key block is too long: " + value);
        }
        return "0000".substring(0, 4 - text.length()) + text;
    }

    private static int parseInt(String text, String what) {
        return parseInt(text, what, 10);
    }

    /**
     * A number of the text.
     *
     * <p>The length of the block is written in decimal and the length of the key in the
     * length encoded block is written in hexadecimal, so the base is a parameter.
     */
    private static int parseInt(String text, String what, int radix) {
        try {
            return Integer.parseInt(text, radix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a number: " + text);
        }
    }

    private static String hexUpper(byte[] bytes) {
        return PaymentCryptographyCrypto.toHex(bytes).toUpperCase(java.util.Locale.ROOT);
    }

    private static byte[] fromHex(String hex) {
        try {
            return PaymentCryptographyCrypto.fromHex(hex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the key block is not hexadecimal");
        }
    }
}
