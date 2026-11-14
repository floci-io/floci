package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.Ciphers;
import io.floci.aws.services.paymentcryptography.crypto.ConcatKdf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 *   "HDC2" || the initialization vector || the key, with the variant
 *            exclusive-ored into its first byte, followed by the key's check
 *            value, encrypted under the wrapping key with CBC || the tag
 * </pre>
 *
 * <p>The tag is what makes the cryptogram more than the output of a cipher. A
 * cipher on its own says nothing about a ciphertext that was altered: a block
 * that was flipped decrypts to a plaintext a block away from the right one, and
 * a three-byte check value is not enough to tell the two apart with any
 * confidence, so a caller who could change a cryptogram could change the key it
 * carries and try again until the check value matched. The tag is an HMAC over
 * the whole of the cryptogram, and it is checked before anything is decrypted,
 * so a cryptogram that was changed is refused without the key it claims to
 * carry ever being computed.
 *
 * <p>The tag is over the variant and the algorithm as well as over the
 * ciphertext, so a cryptogram wrapped for one variant does not open under
 * another, and one that names another algorithm does not open either.
 *
 * <p>The cipher is the one the wrapping key's own algorithm is, and the vector
 * is a fresh one for every cryptogram, so two cryptograms of the same key
 * under the same wrapping key are not the same bytes.
 *
 * <p>The two keys the cryptogram uses, the one that encrypts and the one that
 * authenticates, are derived from the wrapping key, each from a label of its
 * own, so neither of them is the wrapping key itself.
 *
 * <p>The check value inside the cryptogram is the key's own check value: the
 * ANSI X9.24 one for a Triple DES key and the CMAC one for an AES key, the same
 * one the service reports as the key's {@code KeyCheckValue}, so the value
 * carried inside the cryptogram and the value in the import response are the
 * same.
 *
 * <p><b>The byte layout of the AS2805 key cryptogram is in the standard and
 * is not published.</b> What the service publishes is the shape - the fields,
 * the variant mapping and the key rules - and those are what this implements.
 * The bytes this reads and writes are therefore this emulator's own
 * construction, and a cryptogram another producer built does not open here.
 */
public final class PaymentCryptographyAs2805Cryptogram {

    /** The format marker, in the clear, at the head of the cryptogram. */
    private static final byte[] MARKER = {'H', 'D', 'C', '2'};

    /** The length of the check value, in bytes. */
    private static final int KCV_BYTES = 3;

    /** The length of the tag, in bytes: an HMAC-SHA-256 is thirty-two of them. */
    private static final int TAG_BYTES = 32;

    /** The label the encryption key is derived under. */
    private static final String ENCRYPTION_LABEL = "floci AS2805 encryption";

    /** The label the authentication key is derived under. */
    private static final String AUTHENTICATION_LABEL = "floci AS2805 authentication";

    /** The digest the derived keys are made with. */
    private static final String DERIVATION_HASH = "SHA_256";

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
        byte[] encryptionKey = derive(wrappingKey, ENCRYPTION_LABEL, wrappingKey.length);
        byte[] authenticationKey = derive(wrappingKey, AUTHENTICATION_LABEL, TAG_BYTES);
        // The vector is fresh for every cryptogram, so the same key under the same wrapping
        // key does not give the same bytes twice.
        byte[] iv = new byte[blockSize(wrappingAlgorithm)];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);
        byte[] body = wrappingAlgorithm.isAes()
                ? Ciphers.aesCbcEncrypt(plain, encryptionKey, iv)
                : Ciphers.tdesCbcEncrypt(plain, Ciphers.widenTdesKey(encryptionKey), iv);
        byte[] tag = tag(authenticationKey, algorithm, variantByte, iv, body);
        // The model's pattern for the cryptogram is upper-case hexadecimal, so that is the
        // case it is written in.
        return HEX_UPPER.formatHex(concat(MARKER, iv, body, tag));
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
        int blockSize = blockSize(wrappingAlgorithm);
        if (payload.length <= MARKER.length + blockSize + TAG_BYTES) {
            throw new IllegalArgumentException("the cryptogram is too short to carry a key");
        }
        for (int i = 0; i < MARKER.length; i++) {
            if (payload[i] != MARKER[i]) {
                throw new IllegalArgumentException(
                        "the cryptogram does not start with the HDC2 marker");
            }
        }
        byte[] iv = new byte[blockSize];
        System.arraycopy(payload, MARKER.length, iv, 0, blockSize);
        int bodyAt = MARKER.length + blockSize;
        int tagAt = payload.length - TAG_BYTES;
        byte[] body = new byte[tagAt - bodyAt];
        System.arraycopy(payload, bodyAt, body, 0, body.length);
        byte[] tag = new byte[TAG_BYTES];
        System.arraycopy(payload, tagAt, tag, 0, TAG_BYTES);

        // Every way the cryptogram can fail says the same thing.
        //
        // <p>The tag, the padding, the length and the check value each fail in their own
        // way, and saying which one failed tells a caller who can change the cryptogram how
        // far its plaintext is from being right. So the failures are one failure here, and
        // the caller is told only that the cryptogram did not open.
        try {
            // The tag is checked first, so a cryptogram that was changed is refused before
            // the key it claims to carry is computed.
            byte[] authenticationKey = derive(wrappingKey, AUTHENTICATION_LABEL, TAG_BYTES);
            if (!MessageDigest.isEqual(tag, tag(authenticationKey, algorithm, variantByte, iv, body))) {
                throw new IllegalArgumentException("the cryptogram was changed");
            }
            byte[] encryptionKey = derive(wrappingKey, ENCRYPTION_LABEL, wrappingKey.length);
            byte[] plain = wrappingAlgorithm.isAes()
                    ? Ciphers.aesCbcDecrypt(body, encryptionKey, iv)
                    : Ciphers.tdesCbcDecrypt(body, Ciphers.widenTdesKey(encryptionKey), iv);
            plain = unpad(plain, blockSize);

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

    /**
     * The tag over the cryptogram.
     *
     * <p>The tag covers the marker, the algorithm and the variant as well as the ciphertext,
     * so a cryptogram is bound to the purpose it was wrapped for and to the algorithm it
     * names.
     *
     * @param authenticationKey the key the tag is made under
     * @param algorithm the algorithm the cryptogram names
     * @param variantByte the variant the cryptogram is bound to
     * @param iv the initialization vector the ciphertext was encrypted under
     * @param body the ciphertext
     * @return the tag
     */
    private static byte[] tag(byte[] authenticationKey, PaymentCryptographyKeyAlgorithm algorithm,
                              int variantByte, byte[] iv, byte[] body) {
        byte[] authenticated = concat(MARKER,
                algorithm.name().getBytes(StandardCharsets.US_ASCII),
                new byte[] {(byte) variantByte}, iv, body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authenticationKey, "HmacSHA256"));
            return mac.doFinal(authenticated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the AS2805 tag could not be computed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * A key derived from the wrapping key, under a label of its own.
     *
     * <p>The encryption key and the authentication key are derived from the same wrapping
     * key under different labels, so neither of them is the wrapping key and neither of them
     * is the other one.
     *
     * @param wrappingKey the key that protects the cryptogram
     * @param label the label the key is derived under
     * @param length how many bytes to derive
     * @return the derived key
     */
    private static byte[] derive(byte[] wrappingKey, String label, int length) {
        return ConcatKdf.derive(DERIVATION_HASH, wrappingKey,
                label.getBytes(StandardCharsets.US_ASCII), length);
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
