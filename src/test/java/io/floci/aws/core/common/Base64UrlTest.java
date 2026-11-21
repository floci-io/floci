package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64UrlTest {

    @Test
    void encodesWithTheUrlAlphabetAndNoPadding() {
        assertEquals("-_8", Base64Url.encode(new byte[] {(byte) 0xfb, (byte) 0xff}));
    }

    @Test
    void encodesTextAsUtf8() {
        assertEquals("w6k", Base64Url.encode("é"));
    }

    @Test
    void decodesUnpaddedTextAsUtf8() {
        assertEquals("é", Base64Url.decode("w6k"));
    }

    @Test
    void decodesPaddedText() {
        assertEquals("é", Base64Url.decode("w6k="));
    }

    @Test
    void refusesTextThatIsNotBase64Url() {
        assertThrows(IllegalArgumentException.class, () -> Base64Url.decode("!!!"));
    }

    /** 65537, the usual RSA exponent, is the three bytes {@code 01 00 01}: "AQAB" in every JWK. */
    @Test
    void encodesAnUnsignedIntegerInItsShortestForm() {
        assertEquals("AQAB", Base64Url.encodeUnsigned(BigInteger.valueOf(65537)));
    }

    /**
     * A value with its top bit set gets a leading zero from {@link BigInteger#toByteArray()} to
     * keep it positive, and the unsigned form drops it: 0xff is one byte, not two.
     */
    @Test
    void dropsTheSignByteBigIntegerAddsWhenTheTopBitIsSet() {
        assertEquals("_w", Base64Url.encodeUnsigned(BigInteger.valueOf(0xff)));
    }

    @Test
    void encodesZeroAsOneZeroByte() {
        assertEquals("AA", Base64Url.encodeUnsigned(BigInteger.ZERO));
    }

    /** An EC coordinate keeps the curve's full length, zero padded on the left. */
    @Test
    void padsAFixedLengthValueOnTheLeft() {
        assertEquals("AAAB", Base64Url.encodeUnsigned(BigInteger.ONE, 3));
        assertEquals("AP8", Base64Url.encodeUnsigned(BigInteger.valueOf(0xff), 2));
    }

    @Test
    void refusesAFixedLengthValueThatDoesNotFit() {
        assertThrows(IllegalArgumentException.class,
                () -> Base64Url.encodeUnsigned(BigInteger.valueOf(0x10000), 2));
    }

    @Test
    void refusesANegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> Base64Url.encodeUnsigned(BigInteger.valueOf(-1)));
    }
}
