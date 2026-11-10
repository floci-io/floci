package io.floci.aws.services.paymentcryptography;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** The hexadecimal the blocks are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

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

    @Test
    void aBlockWithOptionalBlocksIsImported() {
        // An optional block sits between the header and the cipher text in the clear, and it
        // is authenticated with it, so the cipher text starts after it.
        // The identifier, the length over two characters and the value. The length covers
        // the whole block, the identifier and the length included, and it is in hexadecimal.
        String block = blockWithOptionalBlock("00" + "0C" + "61626364");

        byte[] key = PaymentCryptographyTr31.unwrap(block,
                PaymentCryptographyCrypto.fromHex(AES_128_KEK));

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(CLEAR_KEY), key);
    }

    @Test
    void anOptionalBlockWithALengthInHexadecimalIsRead() {
        // The length of an optional block is in hexadecimal, so a length of "0E" is fourteen
        // characters and not a number at all.
        String block = blockWithOptionalBlock("TT" + "0E" + "0123456789");

        byte[] key = PaymentCryptographyTr31.unwrap(block,
                PaymentCryptographyCrypto.fromHex(AES_128_KEK));

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(CLEAR_KEY), key);
    }

    @Test
    void theOptionalBlockTheReferenceGivesIsWrittenAsItGivesIt() {
        // The reference gives the base derivation key identifier of a DUKPT key as "BI" with
        // the value "00ABCDEFABCD", and its length is written as "10": the whole block is
        // sixteen characters, the identifier and the length included.
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_128_KEK), true,
                "A", "D0", "B", "E", "00",
                List.<String[]>of(new String[] {"BI", "00ABCDEFABCD"}));

        assertEquals("BI1000ABCDEFABCD", block.substring(16, 32),
                "the optional block was not written as the standard says");
    }

    @Test
    void aBlockIsPaddedSoThatItsOptionalBlocksStayAligned() {
        // The optional blocks take up a whole number of cipher blocks, so a block whose
        // optional blocks are not aligned gets a padding block that makes them so. The
        // optional block here is twenty-four characters and a cipher block is sixteen, so
        // the padding block carries four characters of filler.
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_128_KEK), true,
                "A", "D0", "B", "E", "00",
                List.<String[]>of(new String[] {"KS", "00604B120F9292800000"}));

        // The optional block and the padding block between the header and the cipher text.
        String optional = block.substring(16, 16 + 32);
        assertEquals("KS1800604B120F9292800000", optional.substring(0, 24),
                "the optional block was not written as the standard says");
        assertEquals("PB080000", optional.substring(24), "the padding block was not added");
        assertEquals(2, Integer.parseInt(block.substring(12, 14)),
                "the padding block was not counted");
    }

    @Test
    void aBlockWhoseOptionalBlocksAreAlignedGetsNoPaddingBlock() {
        // Optional blocks that already fill whole cipher blocks are left alone, because a
        // padding block would break the alignment. Twelve characters of data make the
        // optional block sixteen characters long, which is one cipher block.
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY),
                PaymentCryptographyCrypto.fromHex(AES_128_KEK), true,
                "A", "D0", "B", "E", "00",
                List.<String[]>of(new String[] {"KS", "00604B120F92"}));

        assertEquals(1, Integer.parseInt(block.substring(12, 14)),
                "a padding block was added to aligned optional blocks");
    }

    @Test
    void aBlockWhoseKeyIsNotAWholeNumberOfBytesIsRefused() {
        // The length the block declares is in bits, and a key is a whole number of bytes.
        String block = blockWithLength("0081");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PaymentCryptographyTr31.unwrap(block,
                        PaymentCryptographyCrypto.fromHex(AES_128_KEK)));

        assertTrue(refused.getMessage().contains("whole number of bytes"), refused.getMessage());
    }

    /**
     * A type D block with an optional block in the clear.
     *
     * <p>The optional block is its identifier over two characters, its length over four and
     * then its value. The header says how many of them there are.
     */
    private String blockWithOptionalBlock(String optional) {
        byte[] lengthEncoded = PaymentCryptographyTr31.lengthEncode(
                PaymentCryptographyCrypto.fromHex(CLEAR_KEY), 16);
        byte[][] derived = PaymentCryptographyTr31.deriveKeys(
                PaymentCryptographyCrypto.fromHex(AES_128_KEK), true);
        int blockLength = 16 + optional.length() + lengthEncoded.length * 2 + 32;
        String header = "D" + String.format("%04d", blockLength) + "D0" + "A" + "B" + "00" + "E"
                + "01" + "00";
        byte[] mac = PaymentCryptographyTr31.computeMac(header + optional, lengthEncoded,
                derived[1], true);
        byte[] cipherText = PaymentCryptographyCrypto.aesCbcEncrypt(derived[0], mac, lengthEncoded);
        return header + optional + HEX.formatHex(cipherText) + HEX.formatHex(mac);
    }

    /**
     * A type D block whose length field says the given bits.
     *
     * <p>The length is over the key the block carries, and the rest of the block is built the
     * way the emulator builds one.
     */
    private String blockWithLength(String length) {
        byte[] clearKey = PaymentCryptographyCrypto.fromHex(CLEAR_KEY);
        byte[] lengthEncoded = new byte[32];
        lengthEncoded[0] = (byte) ((Integer.parseInt(length, 16) >> 8) & 0xff);
        lengthEncoded[1] = (byte) (Integer.parseInt(length, 16) & 0xff);
        System.arraycopy(clearKey, 0, lengthEncoded, 2, clearKey.length);
        byte[][] derived = PaymentCryptographyTr31.deriveKeys(
                PaymentCryptographyCrypto.fromHex(AES_128_KEK), true);
        int blockLength = 16 + lengthEncoded.length * 2 + 32;
        String header = "D" + String.format("%04d", blockLength) + "D0" + "A" + "B" + "00" + "E"
                + "00" + "00";
        byte[] mac = PaymentCryptographyTr31.computeMac(header, lengthEncoded, derived[1], true);
        byte[] cipherText = PaymentCryptographyCrypto.aesCbcEncrypt(derived[0], mac, lengthEncoded);
        return header + HEX.formatHex(cipherText) + HEX.formatHex(mac);
    }

}
