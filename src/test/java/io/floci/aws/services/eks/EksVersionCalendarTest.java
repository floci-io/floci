package io.floci.aws.services.eks;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksVersionCalendarTest {

    @Test
    void aVersionLeavesStandardSupportAtTheStartOfItsEndDateInUtc() {
        assertTrue(EksVersionCalendar.inStandardSupport("1.31", Instant.parse("2025-11-25T23:59:59Z")));
        assertFalse(EksVersionCalendar.inStandardSupport("1.31", Instant.parse("2025-11-26T00:00:00Z")));
        assertFalse(EksVersionCalendar.inStandardSupport("1.28", Instant.parse("2026-11-23T00:00:00Z")));
    }

    @Test
    void aVersionTheCalendarDoesNotListYetIsInStandardSupport() {
        assertTrue(EksVersionCalendar.inStandardSupport("1.38", Instant.parse("2030-01-01T00:00:00Z")));
    }
}
