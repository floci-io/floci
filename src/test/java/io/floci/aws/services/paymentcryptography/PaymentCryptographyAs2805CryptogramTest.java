package io.floci.aws.services.paymentcryptography;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The AS2805 key cryptogram.
 *
 * <p>The cryptogram is this emulator's own construction, so the tests here are about the
 * rules it has to follow: a cryptogram opens under the key it was wrapped under and under
 * the variant it was bound to, and a cryptogram that was changed in any way does not open
 * at all.
 */
class PaymentCryptographyAs2805CryptogramTest {

    /** The hexadecimal the cryptograms are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** The variant the PIN encryption key is bound to. */
    private static final int PIN_VARIANT = 0x1C;

    /** The variant the data encryption key is bound to. */
    private static final int DATA_VARIANT = 0x16;

    /** The length of the marker, in bytes. */
    private static final int MARKER_BYTES = 4;

    /** The length of the initialization vector, in bytes. */
    private static final int IV_BYTES = 16;

    /** The length of the tag, in bytes. */
    private static final int TAG_BYTES = 32;

    /** The byte the key the cryptogram carries starts at. */
    private static final int KEY_SEED = 0x00;

    /** The byte the other key the cryptogram carries starts at. */
    private static final int OTHER_KEY_SEED = 0x10;

    /** The byte the wrapping key starts at. */
    private static final int WRAPPING_KEY_SEED = 0xF0;

    /** The byte the other wrapping key starts at. */
    private static final int OTHER_WRAPPING_KEY_SEED = 0x20;

    @ParameterizedTest
    @EnumSource(value = PaymentCryptographyKeyAlgorithm.class,
            names = {"AES_128", "AES_192", "AES_256", "TDES_2KEY", "TDES_3KEY"})
    void aCryptogramComesBackUnderTheKeyItWasWrappedUnder(PaymentCryptographyKeyAlgorithm algorithm) {
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);

        String cryptogram = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        byte[] clear = unwrap(cryptogram, PIN_VARIANT, algorithm, wrappingKey);

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(key), clear);
    }

    @ParameterizedTest
    @ValueSource(strings = {"marker", "ciphertext", "tag"})
    void aCryptogramThatWasChangedDoesNotOpen(String part) {
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);
        String cryptogram = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        String changed = changed(cryptogram, atOf(part, cryptogram));

        assertThrows(IllegalArgumentException.class,
                () -> unwrap(changed, PIN_VARIANT, algorithm, wrappingKey));
    }

    @Test
    void aCryptogramThatCarriesAnotherKeysCiphertextDoesNotOpen() {
        // The check value inside the cryptogram is the check value of the key the
        // ciphertext carries, so a ciphertext of another key passes it. The tag is over the
        // ciphertext, so the substitution is caught.
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);
        String cryptogram = wrap(keyOf(algorithm.bytes(), KEY_SEED), PIN_VARIANT, algorithm,
                wrappingKey);
        String other = wrap(keyOf(algorithm.bytes(), OTHER_KEY_SEED), PIN_VARIANT, algorithm,
                wrappingKey);

        String spliced = splice(cryptogram, other);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unwrap(spliced, PIN_VARIANT, algorithm, wrappingKey));

        assertEquals("the cryptogram did not open under the wrapping key", refused.getMessage());
    }

    @Test
    void aCryptogramUnderOneVariantDoesNotOpenUnderAnother() {
        // The tag is over the variant, so a cryptogram bound to one purpose does not open
        // under another even though the key it carries is the same.
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);
        String cryptogram = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unwrap(cryptogram, DATA_VARIANT, algorithm, wrappingKey));

        assertEquals("the cryptogram did not open under the wrapping key", refused.getMessage());
    }

    @Test
    void aCryptogramWhoseAlgorithmWasChangedDoesNotOpen() {
        // The tag is over the algorithm as well, so a cryptogram that names another
        // algorithm does not open under it.
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);
        String cryptogram = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unwrap(cryptogram, PIN_VARIANT, PaymentCryptographyKeyAlgorithm.AES_128,
                        wrappingKey));

        assertEquals("the cryptogram did not open under the wrapping key", refused.getMessage());
    }

    @Test
    void aCryptogramUnderOneWrappingKeyDoesNotOpenUnderAnother() {
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);
        String cryptogram = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unwrap(cryptogram, PIN_VARIANT, algorithm,
                        keyOf(algorithm.bytes(), OTHER_WRAPPING_KEY_SEED)));

        assertEquals("the cryptogram did not open under the wrapping key", refused.getMessage());
    }

    @Test
    void twoCryptogramsOfTheSameKeyAreNotTheSameBytes() {
        // The vector is fresh for every cryptogram, so the same key under the same wrapping
        // key does not give the same bytes twice.
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.AES_256;
        String key = keyOf(algorithm.bytes(), KEY_SEED);
        String wrappingKey = keyOf(algorithm.bytes(), WRAPPING_KEY_SEED);

        String first = wrap(key, PIN_VARIANT, algorithm, wrappingKey);
        String second = wrap(key, PIN_VARIANT, algorithm, wrappingKey);

        assertNotEquals(first, second);
    }

    @Test
    void aCryptogramThatIsNotHexadecimalIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> unwrap("not hexadecimal", PIN_VARIANT, PaymentCryptographyKeyAlgorithm.AES_256,
                        keyOf(PaymentCryptographyKeyAlgorithm.AES_256.bytes(), WRAPPING_KEY_SEED)));
    }

    /**
     * The cryptogram of a key under a wrapping key.
     *
     * <p>The wrapping key has the algorithm of the key it carries, so the cryptogram is
     * written under the cipher of that algorithm.
     *
     * @param key the key that is carried, in hexadecimal
     * @param variant the variant the key is bound to
     * @param algorithm the algorithm of the key
     * @param wrappingKey the key the cryptogram is wrapped under, in hexadecimal
     * @return the cryptogram, in hexadecimal
     */
    private static String wrap(String key, int variant, PaymentCryptographyKeyAlgorithm algorithm,
                               String wrappingKey) {
        return PaymentCryptographyAs2805Cryptogram.wrap(
                PaymentCryptographyCrypto.fromHex(key), variant, algorithm,
                PaymentCryptographyCrypto.fromHex(wrappingKey), algorithm);
    }

    /**
     * The key a cryptogram opens under.
     *
     * @param cryptogram the cryptogram, in hexadecimal
     * @param variant the variant the key is bound to
     * @param algorithm the algorithm of the key
     * @param wrappingKey the key the cryptogram is wrapped under, in hexadecimal
     * @return the clear key
     */
    private static byte[] unwrap(String cryptogram, int variant,
                                 PaymentCryptographyKeyAlgorithm algorithm, String wrappingKey) {
        return PaymentCryptographyAs2805Cryptogram.unwrap(cryptogram, variant, algorithm,
                PaymentCryptographyCrypto.fromHex(wrappingKey), algorithm);
    }

    /**
     * A key of the given length, in hexadecimal.
     *
     * <p>The seed tells the key apart from the other keys of the same length, so a key and
     * the wrapping key that carries it are never the same bytes.
     *
     * @param length the length of the key, in bytes
     * @param seed the byte the key starts at
     * @return the key
     */
    private static String keyOf(int length, int seed) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < length; i++) {
            hex.append(String.format("%02X", (seed + i) & 0xFF));
        }
        return hex.toString();
    }

    /**
     * The byte a part of the cryptogram is at.
     *
     * @param part the part of the cryptogram
     * @param cryptogram the cryptogram, in hexadecimal
     * @return the byte, counting from the front
     */
    private static int atOf(String part, String cryptogram) {
        return switch (part) {
            case "marker" -> 0;
            case "ciphertext" -> MARKER_BYTES + IV_BYTES;
            case "tag" -> HEX.parseHex(cryptogram).length - 1;
            default -> throw new IllegalArgumentException("no part named " + part);
        };
    }

    /**
     * The cryptogram with one of its bytes changed.
     *
     * @param cryptogram the cryptogram, in hexadecimal
     * @param at the byte that is changed, counting from the front
     * @return the changed cryptogram
     */
    private static String changed(String cryptogram, int at) {
        byte[] bytes = HEX.parseHex(cryptogram);
        bytes[at] = (byte) (bytes[at] ^ 0x01);
        return HEX.formatHex(bytes);
    }

    /**
     * One cryptogram with the encrypted part of another one.
     *
     * <p>The marker and the tag are the first one's, and the vector and the ciphertext are
     * the second one's, so the ciphertext is not the one the tag was made over. The
     * ciphertext decrypts under the first cryptogram's own vector, because the second
     * cryptogram carries its own.
     *
     * @param cryptogram the cryptogram the marker and the tag come from
     * @param other the cryptogram the vector and the ciphertext come from
     * @return the spliced cryptogram
     */
    private static String splice(String cryptogram, String other) {
        byte[] first = HEX.parseHex(cryptogram);
        byte[] second = HEX.parseHex(other);
        byte[] spliced = new byte[first.length];
        System.arraycopy(first, 0, spliced, 0, MARKER_BYTES);
        System.arraycopy(second, MARKER_BYTES, spliced, MARKER_BYTES,
                second.length - MARKER_BYTES - TAG_BYTES);
        System.arraycopy(first, first.length - TAG_BYTES, spliced, spliced.length - TAG_BYTES,
                TAG_BYTES);
        return HEX.formatHex(spliced);
    }
}
