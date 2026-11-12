package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.Ciphers;

import java.util.HexFormat;

/**
 * The AS2805 key cryptogram, as this emulator reads and writes it.
 *
 * <p>AS2805 protects a key by variant rather than by a key block. The variant
 * says what the key is for, and it is exclusive-ored into the key before it is
 * encrypted, so a key wrapped for one purpose does not open under another.
 *
 * <p>The cryptogram this emulator reads is:
 *
 * <pre>
 *   "HDC1" || the key, with the variant exclusive-ored into its first byte,
 *            followed by the key's check value, encrypted under the wrapping
 *            key with CBC and a zero IV
 * </pre>
 *
 * <p>The check value is what tells a wrong wrapping key from a right one.
 * Without it the cipher would decrypt to the right number of bytes under any
 * key at all, and a cryptogram wrapped under one wrapping key would import
 * under another as a key that is silently wrong. The check value is the ANSI
 * X9.24 one the service reports as the key's {@code KeyCheckValue}, so the
 * value carried inside the cryptogram and the value in the import response are
 * the same.
 *
 * <p>Because the check value covers the key <em>after</em> the variant is
 * exclusive-ored back out, it catches a wrong variant as well as a wrong
 * wrapping key: both produce a recovered key whose check value does not match.
 *
 * <p><b>The byte layout of the AS2805 key cryptogram is in the standard and
 * is not published.</b> What the service publishes is the shape - the fields,
 * the variant mapping and the key rules - and those are what this implements.
 * The bytes this reads and writes are therefore this emulator's own
 * construction, and a cryptogram the service produced will not open here.
 */
public final class PaymentCryptographyAs2805Cryptogram {

    /** The format marker, in the clear, at the head of the cryptogram. */
    private static final byte[] MARKER = {'H', 'D', 'C', '1'};

    /** The length of the check value, in bytes. */
    private static final int KCV_BYTES = 3;

    /** The hexadecimal the cryptogram is written in. */
    private static final HexFormat HEX = HexFormat.of();

    /** The hexadecimal the cryptogram is written in: the capitals the model asks for. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    private PaymentCryptographyAs2805Cryptogram() {
    }

    /**
     * Wrap a clear key into an AS2805 cryptogram.
     *
     * @param clearKey the key material
     * @param variantByte the byte that binds the purpose into the key
     * @param algorithm the clear key's algorithm, which fixes its length and
     *     how its check value is computed
     * @param wrappingKey the key that protects the cryptogram
     * @param wrappingAlgorithm the wrapping key's algorithm, which picks the
     *     cipher
     * @return the cryptogram, in hex
     */
    public static String wrap(byte[] clearKey, int variantByte, PaymentCryptographyKeyAlgorithm algorithm,
                              byte[] wrappingKey, PaymentCryptographyKeyAlgorithm wrappingAlgorithm) {
        int expected = expectedBytes(algorithm);
        if (clearKey.length != expected) {
            throw new IllegalArgumentException("the key is " + clearKey.length
                    + " bytes; " + algorithm.name() + " needs " + expected);
        }
        byte[] bound = bind(clearKey, variantByte);
        byte[] check = checkValue(clearKey, algorithm);
        byte[] plain = pad(concat(bound, check), blockSize(wrappingAlgorithm));
        byte[] body = wrappingAlgorithm.isAes()
                ? Ciphers.aesCbcEncrypt(plain, wrappingKey, new byte[16])
                : Ciphers.tdesCbcEncrypt(plain, Ciphers.widenTdesKey(wrappingKey), new byte[8]);
        // The model's pattern for the cryptogram is upper-case hexadecimal, so that is the
        // case it is written in.
        return HEX_UPPER.formatHex(concat(MARKER, body));
    }

    /**
     * Open an AS2805 cryptogram.
     *
     * @param cryptogram the cryptogram, in hex
     * @param variantByte the byte that binds the purpose into the key
     * @param algorithm the clear key's algorithm, which fixes its length and
     *     how its check value is computed
     * @param wrappingKey the key that protects the cryptogram
     * @param wrappingAlgorithm the wrapping key's algorithm, which picks the
     *     cipher
     * @return the clear key
     * @throws IllegalArgumentException when the cryptogram is malformed, does
     *     not decrypt under the wrapping key, or does not carry the check
     *     value of the key it claims to carry
     */
    public static byte[] unwrap(String cryptogram, int variantByte, PaymentCryptographyKeyAlgorithm algorithm,
                                byte[] wrappingKey, PaymentCryptographyKeyAlgorithm wrappingAlgorithm) {
        if (cryptogram == null || !isHex(cryptogram)) {
            throw new IllegalArgumentException("the cryptogram is not hexadecimal");
        }
        byte[] payload = HEX.parseHex(cryptogram);
        if (payload.length <= MARKER.length) {
            throw new IllegalArgumentException("the cryptogram is too short to carry a key");
        }
        for (int i = 0; i < MARKER.length; i++) {
            if (payload[i] != MARKER[i]) {
                throw new IllegalArgumentException(
                        "the cryptogram does not start with the HDC1 marker");
            }
        }
        byte[] body = new byte[payload.length - MARKER.length];
        System.arraycopy(payload, MARKER.length, body, 0, body.length);

        // Every way the cryptogram can fail says the same thing.
        //
        // <p>The padding, the length and the check value each fail in their own way, and
        // saying which one failed tells a caller who can change the cryptogram how far its
        // plaintext is from being right. So the three failures are one failure here, and the
        // caller is told only that the cryptogram did not open.
        try {
            byte[] plain = wrappingAlgorithm.isAes()
                    ? Ciphers.aesCbcDecrypt(body, wrappingKey, new byte[16])
                    : Ciphers.tdesCbcDecrypt(body, Ciphers.widenTdesKey(wrappingKey), new byte[8]);
            plain = unpad(plain, blockSize(wrappingAlgorithm));

            int expected = expectedBytes(algorithm);
            if (plain.length != expected + KCV_BYTES) {
                throw new IllegalArgumentException("the cryptogram carries the wrong length");
            }
            byte[] bound = new byte[expected];
            System.arraycopy(plain, 0, bound, 0, expected);
            byte[] carried = new byte[KCV_BYTES];
            System.arraycopy(plain, expected, carried, 0, KCV_BYTES);

            byte[] clear = unbind(bound, variantByte);
            String expectedCheck = checkValueHex(clear, algorithm);
            String carriedCheck = HEX_UPPER.formatHex(carried);
            if (!expectedCheck.equals(carriedCheck)) {
                throw new IllegalArgumentException("the cryptogram carries the wrong check value");
            }
            return clear;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the cryptogram did not open under the wrapping key");
        }
    }

    /** The key, with the variant exclusive-ored into its first byte. */
    private static byte[] bind(byte[] clearKey, int variantByte) {
        byte[] bound = clearKey.clone();
        bound[0] ^= (byte) variantByte;
        return bound;
    }

    /** The key, with the variant exclusive-ored back out. */
    private static byte[] unbind(byte[] bound, int variantByte) {
        byte[] clear = bound.clone();
        clear[0] ^= (byte) variantByte;
        return clear;
    }

    /** The check value of the key, in bytes. */
    private static byte[] checkValue(byte[] clearKey, PaymentCryptographyKeyAlgorithm algorithm) {
        return HEX.parseHex(checkValueHex(clearKey, algorithm));
    }

    /** Whether the text is non-empty and entirely hexadecimal. */
    private static boolean isHex(String text) {
        if (text == null || text.isEmpty() || text.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    /** The check value of the key, in upper-case hex. */
    private static String checkValueHex(byte[] clearKey, PaymentCryptographyKeyAlgorithm algorithm) {
        return PaymentCryptographyCrypto.checkValue(clearKey, algorithm);
    }
    /** The key length an algorithm implies. */
    private static int expectedBytes(PaymentCryptographyKeyAlgorithm algorithm) {
        if (algorithm == null || algorithm.isEcc()) {
            throw new IllegalArgumentException(
                    "the key algorithm " + algorithm + " is not a symmetric algorithm");
        }
        return algorithm.bytes();
    }

    /** The cipher's block size, which the padding works to. */
    private static int blockSize(PaymentCryptographyKeyAlgorithm algorithm) {
        return algorithm.isAes() ? 16 : 8;
    }

    /**
     * PKCS#5 padding.
     *
     * <p>A whole block is added when the input already fills one, so the
     * padding is never ambiguous.
     */
    private static byte[] pad(byte[] data, int blockSize) {
        int padLength = blockSize - (data.length % blockSize);
        byte[] out = new byte[data.length + padLength];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = 0; i < padLength; i++) {
            out[data.length + i] = (byte) padLength;
        }
        return out;
    }

    /** Strip PKCS#5 padding, and refuse anything that is not properly padded. */
    private static byte[] unpad(byte[] data, int blockSize) {
        if (data.length == 0 || data.length % blockSize != 0) {
            throw new IllegalArgumentException("the cryptogram did not decrypt to whole blocks");
        }
        int padLength = data[data.length - 1] & 0xFF;
        if (padLength < 1 || padLength > blockSize || padLength > data.length) {
            throw new IllegalArgumentException(
                    "the cryptogram did not decrypt under the wrapping key");
        }
        for (int i = data.length - padLength; i < data.length; i++) {
            if ((data[i] & 0xFF) != padLength) {
                throw new IllegalArgumentException(
                        "the cryptogram did not decrypt under the wrapping key");
            }
        }
        byte[] out = new byte[data.length - padLength];
        System.arraycopy(data, 0, out, 0, out.length);
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
