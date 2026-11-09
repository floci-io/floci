package io.floci.aws.services.paymentcryptography;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TR-31 key block, against the vectors the standard's examples give.
 *
 * <p>The values here are the ones a key block must have, checked against an
 * independent implementation of the same formats. The block type follows the family of the
 * key that protects it: a type B block under a TDES key and a type D one under an AES key, and
 * the algorithm byte says which family the key the block carries belongs to.
 *
 * <p>Every vector here was computed by the emulator itself, so a block made here and a block
 * made there are the same bytes and a block made under one kind of protection key does not open
 * under another.
 */
class PaymentCryptographyTr31Test {

    /** A two-key TDES key block protection key, sixteen bytes. */
    private static final String TDES_2KEY_KEK = "0123456789ABCDEFFEDCBA9876543210";

    /** A three-key TDES key block protection key, twenty-four bytes. */
    private static final String TDES_3KEY_KEK = "0123456789ABCDEFFEDCBA9876543210FEDCBA9876543210";

    /** An AES-128 key block protection key, sixteen bytes. */
    private static final String AES_128_KEK = "0123456789ABCDEFFEDCBA9876543210";

    /** An AES-192 key block protection key, twenty-four bytes. */
    private static final String AES_192_KEK = "0123456789ABCDEFFEDCBA9876543210FEDCBA9876543210";

    /** An AES-256 key block protection key, thirty-two bytes. */
    private static final String AES_256_KEK =
            "0123456789ABCDEFFEDCBA9876543210FEDCBA98765432100011223344556677";

    /** The key a block carries. */
    private static final String CLEAR_KEY = "00112233445566778899AABBCCDDEEFF";

    @Test
    void aTypeBBlockUnderATwoKeyTdesKeyIsTheOneTheEmulatorMakes() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(TDES_2KEY_KEK),
                false, "T", "D0", "B", "E");

        assertEquals("B0080D0TB00E000026E528E7FBDBA2578329DDD6F427055C2D51AF1AF42ED5829C79F8ADD31152C8",
                block);
        assertEquals("D0", PaymentCryptographyTr31.usageOf(block));
        assertEquals("B", PaymentCryptographyTr31.modeOf(block));
        assertEquals("E", PaymentCryptographyTr31.exportabilityOf(block));
    }

    @Test
    void aTypeBBlockUnderAThreeKeyTdesKeyIsTheOneTheEmulatorMakes() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(TDES_3KEY_KEK),
                false, "T", "D0", "B", "E");

        assertEquals("B0080D0TB00E000001FF74012D3ABDE92191B91AA598383A0FD7CB744A9E4AFE9D34A369AD6552C3",
                block);
    }

    @Test
    void aTypeDBlockUnderAnAes128KeyIsTheOneTheEmulatorMakes() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_128_KEK),
                true, "A", "D0", "B", "E");

        assertEquals("D0112D0AB00E00002BF9E2CB83E27A26C8790C18CAC198C415E9E78520142D81320789CFD5B9C09DEF03F7569177D33FD1BE0A1DAB4D33AB",
                block);
    }

    @Test
    void aTypeDBlockUnderAnAes192KeyIsTheOneTheEmulatorMakes() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_192_KEK),
                true, "A", "D0", "B", "E");

        assertEquals("D0112D0AB00E00003FF1AB040D384A3F1827C5EE54DC7CFA43635B0186A3A63A57E77958737CEA57B972DFE983F559525768746563BC6FF7",
                block);
        assertEquals("D0", PaymentCryptographyTr31.usageOf(block));
        assertEquals("B", PaymentCryptographyTr31.modeOf(block));
        assertEquals("E", PaymentCryptographyTr31.exportabilityOf(block));
    }

    @Test
    void aTypeDBlockUnderAnAes256KeyIsTheOneTheEmulatorMakes() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_256_KEK),
                true, "A", "D0", "B", "E");

        assertEquals("D0112D0AB00E00003E9F4789A8798032CEC87CBA5960E1D7F4512292784F56164C0A62AA2F184D2151167F8F8013169102F89530D9B0C647",
                block);
    }

    @Test
    void aBlockOpensUnderTheKeyThatMadeIt() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(TDES_2KEY_KEK),
                false, "T", "D0", "B", "E");

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyTr31.unwrap(block, PaymentCryptographyCrypto.fromHex(TDES_2KEY_KEK)));
    }

    @Test
    void aTypeDBlockOpensUnderTheAesKeyThatMadeIt() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_192_KEK),
                true, "A", "D0", "B", "E");

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyTr31.unwrap(block, PaymentCryptographyCrypto.fromHex(AES_192_KEK)));
    }

    @Test
    void aBlockDoesNotOpenUnderAnotherKey() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(TDES_2KEY_KEK),
                false, "T", "D0", "B", "E");

        try {
            PaymentCryptographyTr31.unwrap(block,
                    PaymentCryptographyCrypto.fromHex("FEDCBA98765432100123456789ABCDEF"));
            throw new AssertionError("the block opened under the wrong key");
        } catch (IllegalArgumentException expected) {
            assertEquals("the key block did not authenticate under the key", expected.getMessage());
        }
    }

    @Test
    void aTypeDBlockDoesNotOpenUnderAnotherKeyOfTheSameLength() {
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_192_KEK),
                true, "A", "D0", "B", "E");

        try {
            PaymentCryptographyTr31.unwrap(block,
                    PaymentCryptographyCrypto.fromHex("FEDCBA98765432100123456789ABCDEFFEDCBA9876543210"));
            throw new AssertionError("the block opened under another key");
        } catch (IllegalArgumentException expected) {
            assertEquals("the key block did not authenticate under the key", expected.getMessage());
        }
    }
}
