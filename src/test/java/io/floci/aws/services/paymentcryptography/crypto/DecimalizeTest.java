package io.floci.aws.services.paymentcryptography.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The decimalization the Visa PIN verification value is made of.
 *
 * <p>The digits of the enciphered block come first, and the letters fill the gap when there
 * are not enough of them: a letter stands for its own value less ten, so A is zero and F is
 * five.
 */
class DecimalizeTest {

    @Test
    void theDigitsComeFirst() {
        assertEquals("1234", Decimalize.of("1234ABCDEF", 4));
    }

    @Test
    void theLettersFillTheGap() {
        // The example the reference gives: the block holds three digits, so the first letter
        // completes the value, and F is five.
        assertEquals("0975", Decimalize.of("0FAB9CDEFFE7DCBA", 4));
    }

    @Test
    void aLetterStandsForItsOwnValueLessTen() {
        assertEquals("0123", Decimalize.of("ABCDEF", 4));
        assertEquals("012345", Decimalize.of("ABCDEF", 6));
    }
}
