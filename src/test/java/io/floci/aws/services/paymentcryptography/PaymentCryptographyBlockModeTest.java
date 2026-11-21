package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.BlockMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The block cipher modes the reference names, and the bytes each of them produces.
 *
 * <p>The values are the ones the standard's own examples give: the key, the vector and the
 * plain text are the ones NIST SP 800-38A uses for its AES-128 examples, so a mode that
 * agrees with them agrees with every other implementation of the standard.
 */
class PaymentCryptographyBlockModeTest {

    /** The key the standard's AES-128 examples use. */
    private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";

    /** The vector the standard's AES-128 examples use. */
    private static final String VECTOR = "000102030405060708090a0b0c0d0e0f";

    /** The plain text the standard's AES-128 examples use. */
    private static final String PLAIN_TEXT = "6bc1bee22e409f96e93d7e117393172a"
            + "ae2d8a571e03ac9c9eb76fac45af8e51"
            + "30c81c46a35ce411e5fbc1191a0a52ef"
            + "f69f2445df4f9b17ad2b417be66c3710";

    /** The cipher text the standard's example for the electronic code book gives. */
    private static final String ECB = "3ad77bb40d7a3660a89ecaf32466ef97"
            + "f5d3d58503b9699de785895a96fdbaaf"
            + "43b1cd7f598ece23881b00e3ed0306887b"
            + "0c785e27e8ad3f8223207104725dd4";

    /** The cipher text the standard's example for the chaining mode gives. */
    private static final String CBC = "7649abac8119b246cee98e9b12e9197d"
            + "5086cb9b507219ee95db113a917678b2"
            + "73bed6b8e3c1743b7116e69e222295163"
            + "ff1caa1681fac09120eca307586e1a7";

    /** The cipher text of the feedback mode over the cipher's own block. */
    private static final String CFB128 = "3b3fd92eb72dad20333449f8e83cfb4a"
            + "c8a64537a0b3a93fcde3cdad9f1ce58b"
            + "26751f67a3cbb140b1808cf187a4f4df"
            + "c04b05357c5d1c0eeac4c66f9ff7f2e6";

    /** The cipher text of the output feedback mode. */
    private static final String OFB = "3b3fd92eb72dad20333449f8e83cfb4a"
            + "7789508d16918f03f53c52dac54ed825"
            + "9740051e9c5fecf64344f7a82260edcc"
            + "304c6528f659c77866a510d9c1d6ae5e";

    /** The cipher text of the feedback mode over eight bits. */
    private static final String CFB8 = "3b79424c9c0dd436bace9e0ed4586a4f"
            + "32b9ded50ae3ba69d472e88267fb5052"
            + "70cbad1e257691f7c47c5038297edda32"
            + "ff26d0ed19174096161ecc14086dd62";

    /** The cipher text of the feedback mode over sixty-four bits. */
    private static final String CFB64 = "3b3fd92eb72dad20764bc8b40ee0de40"
            + "f857ab76f3e7bc33332265ff0594b12e"
            + "6c8bf2f3fc1ba87b2f124a56f7fe88d23"
            + "41f1d0535f0d56e58287bbec2952b2a";

    /** The cipher text of the feedback mode over a single bit. */
    private static final String CFB1 = "68b3a264f838f5f8c3101070d1ab4c2e"
            + "22e7f950383a0b71ade4fad0095cb188"
            + "a57972c3c1882615f7511411fbebf1193"
            + "997069704fc1d1f27028434c99e60f4";

    @Test
    void everyModeTheReferenceNamesIsMade() {
        for (String name : new String[] {"ECB", "CBC", "CFB", "CFB1", "CFB8", "CFB64", "CFB128",
                "OFB"}) {
            assertEquals(name, BlockMode.from(name).name());
        }
        assertEquals(null, BlockMode.from("ROT13"));
    }

    @Test
    void theElectronicCodeBookModeIsTheOneTheStandardGives() {
        assertEquals(ECB, cipherText(BlockMode.ECB, null));
    }

    @Test
    void theChainingModeIsTheOneTheStandardGives() {
        assertEquals(CBC, cipherText(BlockMode.CBC, VECTOR));
    }

    @Test
    void theFeedbackModeOverTheBlockIsTheOneTheStandardGives() {
        assertEquals(CFB128, cipherText(BlockMode.CFB128, VECTOR));
        // The feedback mode with no number is the one over the cipher's own block.
        assertEquals(CFB128, cipherText(BlockMode.CFB, VECTOR));
    }

    @Test
    void theOutputFeedbackModeIsTheOneTheStandardGives() {
        assertEquals(OFB, cipherText(BlockMode.OFB, VECTOR));
    }

    @Test
    void theFeedbackModeOverEightBitsIsTheOneAnotherImplementationComputes() {
        assertEquals(CFB8, cipherText(BlockMode.CFB8, VECTOR));
    }

    @Test
    void theFeedbackModeOverSixtyFourBitsIsTheOneAnotherImplementationComputes() {
        assertEquals(CFB64, cipherText(BlockMode.CFB64, VECTOR));
    }

    @Test
    void theFeedbackModeOverOneBitIsTheOneTheStandardGives() {
        assertEquals(CFB1, cipherText(BlockMode.CFB1, VECTOR));
    }

    @Test
    void everyModeDecryptsWhatItEncrypted() {
        for (BlockMode mode : BlockMode.values()) {
            byte[] key = PaymentCryptographyCrypto.fromHex(KEY);
            byte[] vector = PaymentCryptographyCrypto.fromHex(VECTOR);
            byte[] plain = PaymentCryptographyCrypto.fromHex(PLAIN_TEXT);
            byte[] cipherText = mode.takesVector()
                    ? PaymentCryptographyCrypto.encrypt(key, plain, vector, mode)
                    : PaymentCryptographyCrypto.encrypt(key, plain, null, mode);
            byte[] back = mode.takesVector()
                    ? PaymentCryptographyCrypto.decrypt(key, cipherText, vector, mode)
                    : PaymentCryptographyCrypto.decrypt(key, cipherText, null, mode);
            assertArrayEquals(plain, back, mode.name());
        }
    }

    @Test
    void theModeThatFeedsABlockBackIsTheOneTheBlockHolds() {
        // The feedback mode over the block enciphers the first block under the vector, so
        // the first cipher text block is the vector enciphered.
        byte[] key = PaymentCryptographyCrypto.fromHex(KEY);
        byte[] vector = PaymentCryptographyCrypto.fromHex(VECTOR);
        byte[] cipherText = PaymentCryptographyCrypto.encrypt(key,
                PaymentCryptographyCrypto.fromHex(PLAIN_TEXT), vector, BlockMode.CFB128);
        assertEquals("3b3fd92eb72dad20333449f8e83cfb4a",
                PaymentCryptographyCrypto.toHex(cipherText).substring(0, 32));
    }

    @Test
    void aModeThatTakesNoVectorIsEncryptedWithoutOne() {
        byte[] key = PaymentCryptographyCrypto.fromHex(KEY);
        byte[] plain = PaymentCryptographyCrypto.fromHex(PLAIN_TEXT);
        assertArrayEquals(plain,
                PaymentCryptographyCrypto.decrypt(key,
                        PaymentCryptographyCrypto.encrypt(key, plain, null, BlockMode.ECB),
                        null, BlockMode.ECB));
    }

    @Test
    void aVectorOfTheWrongLengthIsRefused() {
        byte[] key = PaymentCryptographyCrypto.fromHex(KEY);
        byte[] plain = PaymentCryptographyCrypto.fromHex(PLAIN_TEXT);
        assertThrows(IllegalArgumentException.class, () -> PaymentCryptographyCrypto.encrypt(key,
                plain, PaymentCryptographyCrypto.fromHex("00112233"), BlockMode.CBC));
    }

    /** The cipher text of the plain text under the key, in the mode. */
    private static String cipherText(BlockMode mode, String vector) {
        byte[] key = PaymentCryptographyCrypto.fromHex(KEY);
        byte[] plain = PaymentCryptographyCrypto.fromHex(PLAIN_TEXT);
        byte[] iv = vector == null ? null : PaymentCryptographyCrypto.fromHex(vector);
        return PaymentCryptographyCrypto.toHex(
                PaymentCryptographyCrypto.encrypt(key, plain, iv, mode));
    }
}
