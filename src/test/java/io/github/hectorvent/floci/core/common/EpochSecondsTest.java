package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpochSecondsTest {

    @Test
    void keepsMillisecondFractionAndDropsSubmillisecondPrecision() {
        assertEquals(1_790_312_384.592, EpochSeconds.fromInstant(
                Instant.ofEpochSecond(1_790_312_384L, 592_999_999L)));
    }

    @Test
    void floorsNegativeSubmillisecondInstantToPreviousMillisecond() {
        assertEquals(-0.001, EpochSeconds.fromInstant(Instant.ofEpochSecond(-1, 999_999_999)));
    }

    @Test
    void convertsLongEpochMillisWithoutLosingMillisecondFraction() {
        assertEquals(1_790_312_384.592, EpochSeconds.fromMillis(1_790_312_384_592L));
        assertEquals(-0.001, EpochSeconds.fromMillis(-1L));
        assertEquals(0.001, EpochSeconds.fromMillis(1L));
    }

    @Test
    void retainsNullAndOverflowFailures() {
        assertThrows(NullPointerException.class, () -> EpochSeconds.fromInstant(null));
        assertThrows(ArithmeticException.class, () -> EpochSeconds.fromInstant(Instant.MAX));
    }
}
