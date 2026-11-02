package io.github.hectorvent.floci.services.paymentcryptography;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The AES-CMAC of RFC 4493, against the examples the RFC gives.
 *
 * <p>The examples cover the three cases the algorithm has: a message of no
 * length, which is padded and masked with K2; a message of whole blocks, which
 * is masked with K1; and a message of blocks and a part, whose last block is
 * padded and masked with K2.
 */
class PaymentCryptographyCryptoTest {

    /** The key of every example the RFC gives. */
    private static final String KEY = "2B7E151628AED2A6ABF7158809CF4F3C";

    /** The CMAC of a message under {@link #KEY}, as hexadecimal. */
    private static String cmac(String message) {
        return PaymentCryptographyCrypto.toHex(
                PaymentCryptographyCrypto.cmac(PaymentCryptographyCrypto.fromHex(KEY),
                        PaymentCryptographyCrypto.fromHex(message)));
    }

    /** RFC 4493's example 1: a message of no length. */
    @Test
    void anEmptyMessageIsTheMacTheRfcGives() {
        assertEquals("bb1d6929e95937287fa37d129b756746", cmac(""));
    }

    /** RFC 4493's example 2: a message of one whole block. */
    @Test
    void aWholeBlockIsTheMacTheRfcGives() {
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c", cmac("6bc1bee22e409f96e93d7e117393172a"));
    }

    /** RFC 4493's example 3: two whole blocks and a padded one. */
    @Test
    void wholeBlocksAndAPaddedOneAreTheMacTheRfcGives() {
        assertEquals("dfa66747de9ae63030ca32611497c827", cmac(
                "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
                        + "30c81c46a35ce411"));
    }

    /** RFC 4493's example 4: four whole blocks. */
    @Test
    void severalWholeBlocksAreTheMacTheRfcGives() {
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", cmac(
                "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
                        + "30c81c46a35ce411e5fbc1191a0a52ef"
                        + "f69f2445df4f9b17ad2b417be66c3710"));
    }

    /**
     * The check value of a key is the leftmost three bytes of the CMAC of
     * sixteen zero bytes under it, which is the CMAC of one whole block.
     */
    @Test
    void theCheckValueIsTheLeftmostThreeBytesOfTheMacOfSixteenZeroBytes() {
        assertEquals("7AD386", PaymentCryptographyCrypto.checkValue(KEY));
    }
}
