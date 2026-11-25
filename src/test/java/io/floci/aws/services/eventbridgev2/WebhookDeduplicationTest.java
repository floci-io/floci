package io.floci.aws.services.eventbridgev2;

import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookDeduplicationTest {
    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-25T06:00:00Z"));
    private final WebhookDeduplication deduplication = new WebhookDeduplication(clock);

    @Test
    void aRepeatInsideTheWindowIsNotFirstSeen() {
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertFalse(deduplication.firstSeen("source-a", "h1"));
    }

    @Test
    void aRepeatAfterFiveMinutesIsFirstSeenAgain() {
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        assertFalse(deduplication.firstSeen("source-a", "h1"));
    }

    @Test
    void theWindowRunsFromTheFirstRequestAndDoesNotSlide() {
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        clock.advance(Duration.ofMinutes(3));
        assertFalse(deduplication.firstSeen("source-a", "h1"));
        clock.advance(Duration.ofMinutes(2));
        assertTrue(deduplication.firstSeen("source-a", "h1"));
    }

    @Test
    void anotherHashAndAnotherSourceAreFirstSeen() {
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        assertTrue(deduplication.firstSeen("source-a", "h2"));
        assertTrue(deduplication.firstSeen("source-b", "h1"));
    }

    @Test
    void forgettingASourceAndClearingDropTheHashes() {
        deduplication.firstSeen("source-a", "h1");
        deduplication.firstSeen("source-b", "h1");

        deduplication.forget("source-a");
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        assertFalse(deduplication.firstSeen("source-b", "h1"));

        deduplication.clear();
        assertTrue(deduplication.firstSeen("source-b", "h1"));
    }

    @Test
    void forgettingOneHashLeavesTheOthersOfTheSource() {
        deduplication.firstSeen("source-a", "h1");
        deduplication.firstSeen("source-a", "h2");

        deduplication.forget("source-a", "h1");
        deduplication.forget("source-never-seen", "h1");

        assertTrue(deduplication.firstSeen("source-a", "h1"));
        assertFalse(deduplication.firstSeen("source-a", "h2"));
    }

    @Test
    void eachHashLeavesFiveMinutesAfterItWasFirstSeen() {
        assertTrue(deduplication.firstSeen("source-a", "h1"));
        clock.advance(Duration.ofMinutes(3));
        assertTrue(deduplication.firstSeen("source-a", "h2"));
        clock.advance(Duration.ofMinutes(2));

        assertTrue(deduplication.firstSeen("source-a", "h1"));
        assertFalse(deduplication.firstSeen("source-a", "h2"));
        clock.advance(Duration.ofMinutes(3));
        assertTrue(deduplication.firstSeen("source-a", "h2"));
        assertFalse(deduplication.firstSeen("source-a", "h1"));
    }
}
