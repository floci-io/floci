package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.Ciphers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    /** The reduction constant of Triple DES. */
    private static final String TDEA_RB = "000000000000001B";

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
     * @param protectionAes whether the protection key is an AES key, which decides the block
     *                      type: a type D block under an AES key and a type B one under a TDES
     *                      key. The length alone does not say it, because a 16-byte key is
     *                      both an AES-128 key and a two-key TDES one.
     * @param algorithm     the algorithm byte of the key the block carries: {@code A} for an
     *                      AES key and {@code T} for a TDES one
     * @param usage         the usage code of the key, such as {@code D0}
     * @param mode          the mode of use code of the key, such as {@code B}
     * @param exportability {@code E} when the key may leave the service again
     * @return the block, in upper-case hexadecimal
     */
    static String wrap(byte[] clearKey, byte[] protectionKey, boolean protectionAes,
                       String algorithm, String usage, String mode, String exportability) {
        return wrap(clearKey, protectionKey, protectionAes, algorithm, usage, mode, exportability,
                "00", List.of());
    }

    /**
     * A key block, with the optional blocks the header carries.
     *
     * <p>The optional blocks sit between the header and the cipher text, in the clear, and
     * they are authenticated with it.
     *
     * @param version the version of the key the block carries, over two characters
     * @param optionalBlocks the optional blocks, each its identifier and its value
     */
    static String wrap(byte[] clearKey, byte[] protectionKey, boolean protectionAes,
                       String algorithm, String usage, String mode, String exportability,
                       String version, List<String[]> optionalBlocks) {
        String type = protectionAes ? "D" : "B";
        byte[] lengthEncoded = lengthEncode(clearKey, protectionAes ? 16 : 8);
        // The optional blocks are padded so that the header keeps the block alignment the
        // standard asks for, and the padding block is one of them.
        List<String[]> padded = withPadding(optionalBlocks, protectionAes ? 16 : 8);
        String optional = optionalBlocks(padded);
        int blockLength = HEADER_LENGTH + optional.length() + lengthEncoded.length * 2
                + macLength(protectionAes);
        String header = header16(type, usage, mode, exportability, blockLength, algorithm, version,
                padded.size());

        byte[][] derived = deriveKeys(protectionKey, protectionAes);
        byte[] encryptionKey = derived[0];
        byte[] macKey = derived[1];
        byte[] mac = computeMac(header + optional, lengthEncoded, macKey, protectionAes);

        // The block is encrypted under the derived key, and the MAC is the vector of that
        // encryption: the block is authenticated before it is read.
        byte[] cipherText = protectionAes
                ? PaymentCryptographyCrypto.aesCbcEncrypt(encryptionKey, mac, lengthEncoded)
                : Ciphers.tdesCbcEncrypt(lengthEncoded, Ciphers.widenTdesKey(encryptionKey), mac);
        return header + optional + hexUpper(cipherText) + hexUpper(mac);
    }

    /**
     * The optional blocks of a header, with the padding block that keeps them aligned.
     *
     * <p>The standard requires the optional blocks to take up a whole number of cipher
     * blocks, so a header that carries them and is not aligned gets a padding block whose
     * length is the smallest that aligns it. A header whose optional blocks are already
     * aligned gets no padding block at all, because one would break the alignment.
     *
     * @param blocks the optional blocks the caller asked for
     * @param blockSize the cipher block size in characters
     * @return the blocks, with the padding block appended when one is needed
     */
    static List<String[]> withPadding(List<String[]> blocks, int blockSize) {
        int total = 0;
        for (String[] block : blocks) {
            total += block[0].length() + 2 + block[1].length();
        }
        if (total % blockSize == 0) {
            return blocks;
        }
        // The padding block spends four characters on its identifier and its length, and
        // the rest of the space is what it carries.
        int padding = blockSize - ((total + 4) % blockSize);
        if (padding == blockSize) {
            padding = 0;
        }
        List<String[]> padded = new ArrayList<>(blocks);
        padded.add(new String[] {"PB", "0".repeat(padding)});
        return padded;
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
        String type = header.substring(0, 1);
        if (!"D".equals(type) && !"B".equals(type)) {
            throw new IllegalArgumentException("the key block is of type " + type
                    + ", and this service reads type B and type D blocks");
        }
        int declared = parseInt(header.substring(1, 5), "the length of the key block");
        if (declared != block.length()) {
            throw new IllegalArgumentException("the key block says it is " + declared
                    + " characters long, and it is " + block.length());
        }
        boolean aes = "D".equals(type);
        int macLength = macLength(aes);
        byte[] mac = fromHex(block.substring(block.length() - macLength));
        // The optional blocks sit between the header and the cipher text in the clear, and
        // they are authenticated with it.
        int optionalLength = optionalLength(header, block);
        String optional = block.substring(HEADER_LENGTH, HEADER_LENGTH + optionalLength);
        byte[] cipherText = fromHex(
                block.substring(HEADER_LENGTH + optionalLength, block.length() - macLength));

        byte[][] derived = deriveKeys(protectionKey, aes);
        byte[] encryptionKey = derived[0];
        byte[] macKey = derived[1];
        byte[] lengthEncoded = aes
                ? PaymentCryptographyCrypto.aesCbcDecrypt(encryptionKey, mac, cipherText)
                : Ciphers.tdesCbcDecrypt(cipherText, Ciphers.widenTdesKey(encryptionKey), mac);

        byte[] recomputed = computeMac(header + optional, lengthEncoded, macKey, aes);
        // The MACs are compared as bytes and in constant time: a comparison that stopped at
        // the first difference would say how much of the MAC matched.
        if (!MessageDigest.isEqual(recomputed, mac)) {
            throw new IllegalArgumentException("the key block did not authenticate under the key");
        }

        int bits = parseInt(hexUpper(Arrays.copyOfRange(lengthEncoded, 0, 2)), "the length of the key", 16);
        if (bits % 8 != 0) {
            throw new IllegalArgumentException("the key block says its key is " + bits
                    + " bits long, which is not a whole number of bytes");
        }
        if (lengthEncoded.length < 2 + bits / 8) {
            throw new IllegalArgumentException("the key block says its key is longer than the block");
        }
        return Arrays.copyOfRange(lengthEncoded, 2, 2 + bits / 8);
    }

    /**
     * The optional blocks a key block carries, as the characters of the key block.
     *
     * <p>An optional block is its identifier over two characters, its length over two, and
     * then its value. The length covers the whole block in characters, the identifier and the
     * length included, and it is written in hexadecimal.
     *
     * @param blocks the optional blocks, each its identifier and its value
     * @return the optional blocks, as the characters of the key block
     */
    static String optionalBlocks(List<String[]> blocks) {
        StringBuilder out = new StringBuilder();
        for (String[] block : blocks) {
            String id = block[0];
            String value = block[1];
            // The length covers the whole block, the identifier and the length included.
            out.append(id).append(two(4 + value.length(), 16)).append(value);
        }
        return out.toString();
    }

    /**
     * How many characters the block's optional blocks take.
     *
     * <p>An optional block is its identifier over two characters, its length over two, and
     * then its value. The length covers the whole block in characters, the identifier and the
     * length included, and it is written in hexadecimal. The header says how many of them
     * there are.
     *
     * @param header the block's header
     * @param block the whole block
     * @return the length of the optional blocks, in characters
     */
    static int optionalLength(String header, String block) {
        int count = parseInt(header.substring(12, 14), "the number of optional blocks");
        int length = 0;
        for (int i = 0; i < count; i++) {
            length += optionalBlockLength(block, HEADER_LENGTH + length);
        }
        return length;
    }

    /**
     * How many characters the optional block at a place takes.
     *
     * <p>The length field says how long the whole block is, the identifier and the length
     * included. A block longer than a length field can hold says so with a zero length, and
     * then the number of characters the length itself takes follows, and the length after
     * that.
     *
     * @param block the whole block
     * @param at where the optional block starts
     * @return the length of the optional block, in characters
     */
    private static int optionalBlockLength(String block, int at) {
        if (block.length() < at + 4) {
            throw new IllegalArgumentException("the key block says it has optional blocks, and it "
                    + "is too short to hold them");
        }
        int length = parseInt(block.substring(at + 2, at + 4),
                "the length of an optional block", 16);
        if (length != 0) {
            return length;
        }
        // The length did not fit in its field, so the field says so and the length follows
        // the number of characters it takes.
        if (block.length() < at + 8) {
            throw new IllegalArgumentException("the key block says it has optional blocks, and it "
                    + "is too short to hold them");
        }
        int digits = parseInt(block.substring(at + 4, at + 6),
                "the length of the length of an optional block", 16);
        if (digits == 0 || block.length() < at + 6 + digits) {
            throw new IllegalArgumentException("the key block says it has optional blocks, and it "
                    + "is too short to hold them");
        }
        return parseInt(block.substring(at + 6, at + 6 + digits),
                "the length of an optional block", 16);
    }

    /** The usage code of a block's header, such as {@code D0}. */
    static String usageOf(String block) {
        return block.substring(5, 7);
    }

    /**
     * Whether a block is a type D one, which is derived with AES.
     *
     * <p>The type is in the block's own header, so it is read from there and not from the
     * wrapping key: a type D block is protected with AES and a type B one with TDES.
     */
    static boolean typeOf(String block) {
        return "D".equals(block.substring(0, 1));
    }

    /** The type of a block, {@code D} or {@code B}, for a message that names it. */
    static String typeName(String block) {
        return block.substring(0, 1);
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
    static String header16(String type, String usage, String mode, String exportability,
                           int blockLength, String algorithm, String version,
                           int optionalBlocks) {
        String header = type
                + pad4(blockLength)
                + usage
                + algorithm     // the algorithm of the key the block carries
                + mode
                + version       // the version of the key
                + exportability
                + two(optionalBlocks, 10)  // the number of optional blocks
                + "00";   // the reserved characters
        if (header.length() != HEADER_LENGTH) {
            throw new IllegalArgumentException("the key block header must be 16 characters");
        }
        return header;
    }

    /** The algorithm the header of a block names for the key it carries. */
    static String algorithmInHeader(String block) {
        return block.substring(7, 8);
    }

    /** The length of the MAC of a block of that type, in characters. */
    private static int macLength(boolean aes) {
        return aes ? 32 : 16;
    }

    /**
     * The two masks of the last block of the MAC: the CMAC subkeys of the MAC key.
     *
     * <p>A last block that needed no padding is masked with the first subkey and one that was
     * padded with the second, which is what the reference implementation does.
     */
    private static byte[][] masksOf(byte[] macKey, boolean aes) {
        if (aes) {
            byte[][] subkeys = PaymentCryptographyCrypto.cmacSubkeys(macKey);
            return new byte[][] {subkeys[0], subkeys[1]};
        }
        byte[] subkeys = cmacSubkeys(macKey, 8, TDEA_RB);
        return new byte[][] {Arrays.copyOfRange(subkeys, 0, 8), Arrays.copyOfRange(subkeys, 8, 16)};
    }

    /**
     * The key as a length encoded block: its length in bits in front of it, zero padded to the
     * block size.
     */
    static byte[] lengthEncode(byte[] clearKey, int blockSize) {
        byte[] body = new byte[2 + clearKey.length];
        body[0] = (byte) ((clearKey.length * 8) >> 8);
        body[1] = (byte) (clearKey.length * 8);
        System.arraycopy(clearKey, 0, body, 2, clearKey.length);
        int padding = (blockSize - (body.length % blockSize)) % blockSize;
        byte[] out = new byte[body.length + padding];
        System.arraycopy(body, 0, out, 0, body.length);
        return out;
    }

    /** The block size of AES, in bytes. */
    private static final int BLOCK_SIZE = 16;

    /**
     * The MAC of a block: the CBC-MAC of the header and the length encoded key under the MAC
     * key, with the last block masked by a subkey of it.
     *
     * <p>A last block that needed no padding is masked with the first subkey and one that was
     * padded with the second, which is what the reference implementation does.
     */
    static byte[] computeMac(String header, byte[] lengthEncoded, byte[] macKey, boolean aes) {
        int blockSize = aes ? BLOCK_SIZE : 8;
        byte[][] masks = masksOf(macKey, aes);
        byte[] data = new byte[header.length() + lengthEncoded.length];
        System.arraycopy(header.getBytes(StandardCharsets.US_ASCII), 0, data, 0, header.length());
        System.arraycopy(lengthEncoded, 0, data, header.length(), lengthEncoded.length);

        byte[] last;
        if (data.length % blockSize != 0) {
            int padding = blockSize - (data.length % blockSize);
            data = Arrays.copyOf(data, data.length + padding);
            data[data.length - padding] = (byte) 0x80;
            last = xor(Arrays.copyOfRange(data, data.length - blockSize, data.length), masks[1]);
        } else {
            last = xor(Arrays.copyOfRange(data, data.length - blockSize, data.length), masks[0]);
        }
        byte[] macInput = Arrays.copyOf(data, data.length);
        System.arraycopy(last, 0, macInput, macInput.length - blockSize, blockSize);
        byte[] out = aes
                ? PaymentCryptographyCrypto.aesCbcEncrypt(macKey, new byte[BLOCK_SIZE], macInput)
                : Ciphers.tdesCbcEncrypt(macInput, Ciphers.widenTdesKey(macKey), new byte[8]);
        return Arrays.copyOfRange(out, out.length - blockSize, out.length);
    }

    /**
     * The two keys a block is made under: the one that encrypts the key and the one that
     * authenticates it.
     *
     * <p>The derivation follows the family of the protection key, because the block type does
     * too: a type D block is derived with AES and a type B one with Triple DES.
     */
    static byte[][] deriveKeys(byte[] protectionKey, boolean aes) {
        return aes ? deriveAesKeys(protectionKey) : deriveTypeBKeys(protectionKey);
    }

    /**
     * The derived keys of a type D block.
     *
     * <p>The derivation is the counter mode key derivation of NIST SP800-108, seeded with the
     * second CMAC subkey of the protection key and encrypted by the protection key itself. The
     * derived key is as long as the protection key: a 192-bit protection key derives a 192-bit
     * key, whose second part is truncated to eight bytes, so the cipher that uses the result is
     * AES-192 rather than a widened AES-256.
     */
    private static byte[][] deriveAesKeys(byte[] protectionKey) {
        int bits = protectionKey.length * 8;
        if (bits != 128 && bits != 192 && bits != 256) {
            throw new IllegalArgumentException("a type D block needs a 128, 192 or 256 bit key block "
                    + "protection key, and this one is " + bits + " bits");
        }
        int derivedBytes = bits / 8;
        int firstPart = 16;
        int secondPart = derivedBytes - firstPart;
        byte[] seed = PaymentCryptographyCrypto.cmacSubkeys(protectionKey)[1];

        byte[] encryptionKey = new byte[derivedBytes];
        byte[] macKey = new byte[derivedBytes];
        System.arraycopy(part(seed, bits, false, 1, protectionKey), 0, encryptionKey, 0, firstPart);
        System.arraycopy(part(seed, bits, true, 1, protectionKey), 0, macKey, 0, firstPart);
        if (secondPart > 0) {
            System.arraycopy(part(seed, bits, false, 2, protectionKey), 0, encryptionKey, firstPart,
                    secondPart);
            System.arraycopy(part(seed, bits, true, 2, protectionKey), 0, macKey, firstPart, secondPart);
        }
        return new byte[][] {encryptionKey, macKey};
    }

    /**
     * The derived keys of a type B block.
     *
     * <p>The derivation is the counter mode key derivation of NIST SP800-108, seeded with the
     * first CMAC subkey of the protection key, and the reduction constant of Triple DES is the
     * whole buffer rather than the top bit alone.
     */
    private static byte[][] deriveTypeBKeys(byte[] protectionKey) {
        int bits = protectionKey.length * 8;
        if (bits != 128 && bits != 192) {
            throw new IllegalArgumentException("a type B block needs a 128 or 192 bit key block "
                    + "protection key, and this one is " + bits + " bits");
        }
        byte[] wide = Ciphers.widenTdesKey(protectionKey);
        int parts = bits == 192 ? 3 : 2;
        byte[] seed = Arrays.copyOfRange(cmacSubkeys(wide, 8, TDEA_RB), 0, 8);
        byte[] encryptionKey = new byte[parts * 8];
        byte[] macKey = new byte[parts * 8];
        for (int counter = 1; counter <= parts; counter++) {
            byte[] input = xor(seed, fromHex(typeBConstant(bits, false, counter)));
            byte[] part = Ciphers.tdes3Encrypt(input, wide);
            System.arraycopy(part, 0, encryptionKey, (counter - 1) * 8, 8);
            input = xor(seed, fromHex(typeBConstant(bits, true, counter)));
            part = Ciphers.tdes3Encrypt(input, wide);
            System.arraycopy(part, 0, macKey, (counter - 1) * 8, 8);
        }
        return new byte[][] {encryptionKey, macKey};
    }

    /** The constant of the type B key derivation. */
    private static String typeBConstant(int bits, boolean mac, int counter) {
        String algorithm = bits == 192 ? "0001" : "0000";
        String use = mac ? "0001" : "0000";
        String keyLength = bits == 192 ? "00C0" : "0080";
        return (counter < 10 ? "0" + counter : Integer.toString(counter))
                + use + "00" + algorithm + keyLength;
    }

    /**
     * The CMAC subkeys of a TDES key.
     *
     * <p>The reduction constant of Triple DES is the whole buffer, which is what the reference
     * implementation does, and it is not the top bit alone.
     */
    private static byte[] cmacSubkeys(byte[] key, int blockSize, String rbHex) {
        byte[] s = Ciphers.tdes3Encrypt(new byte[blockSize], key);
        byte[] rb = fromHex(rbHex);
        byte[] k1 = shiftXor(s, rb);
        byte[] k2 = shiftXor(k1, rb);
        byte[] out = new byte[k1.length + k2.length];
        System.arraycopy(k1, 0, out, 0, k1.length);
        System.arraycopy(k2, 0, out, k1.length, k2.length);
        return out;
    }

    /** A left shift by one bit, then the reduction constant when the top bit was set. */
    private static byte[] shiftXor(byte[] value, byte[] rb) {
        byte[] out = new byte[value.length];
        int carry = 0;
        for (int i = value.length - 1; i >= 0; i--) {
            int nextCarry = (value[i] & 0x80) != 0 ? 1 : 0;
            out[i] = (byte) (((value[i] << 1) | carry) & 0xff);
            carry = nextCarry;
        }
        if ((value[0] & 0x80) != 0) {
            for (int i = 0; i < out.length; i++) {
                out[i] ^= rb[i % rb.length];
            }
        }
        return out;
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

    /** Two byte arrays, one after the other. */

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

    /**
     * A number of two characters, in the radix the field it goes into is written in.
     *
     * <p>The header's count of the optional blocks is written in ten, and the length of an
     * optional block in sixteen, as the standard has it.
     *
     * @param value the number
     * @param radix the radix the field is written in
     * @return the number, in two characters
     */
    static String two(int value, int radix) {
        String text = Integer.toString(value, radix).toUpperCase(Locale.ROOT);
        if (text.length() > 2) {
            throw new IllegalArgumentException("the number does not fit in two characters: " + value);
        }
        return "00".substring(0, 2 - text.length()) + text;
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
        return PaymentCryptographyCrypto.toHex(bytes).toUpperCase(Locale.ROOT);
    }

    private static byte[] fromHex(String hex) {
        try {
            return PaymentCryptographyCrypto.fromHex(hex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the key block is not hexadecimal");
        }
    }
}
