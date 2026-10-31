package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManualClockTest {
    @Test
    void readsStayFrozenUntilExplicitlyAdvanced() {
        Instant start = Instant.parse("2026-10-29T00:00:00.123456789Z");
        ManualClock clock = new ManualClock(start);

        assertEquals(start, clock.instant());
        assertEquals(start, clock.instant());
        clock.advance(Duration.ofNanos(1));
        assertEquals(start.plusNanos(1), clock.instant());
        assertEquals(start.plusNanos(1), clock.instant());
    }

    @Test
    void zonedViewsShareTheManuallyAdvancedInstant() {
        Instant start = Instant.parse("2026-10-29T00:00:00Z");
        ManualClock clock = new ManualClock(start);
        Clock zoned = clock.withZone(ZoneOffset.ofHours(7));

        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertEquals(ZoneOffset.ofHours(7), zoned.getZone());
        assertEquals(start, zoned.instant());
        clock.advance(Duration.ofSeconds(60));
        assertEquals(clock.instant(), zoned.instant());
        assertEquals(start.plusSeconds(60), zoned.instant());
    }
}
