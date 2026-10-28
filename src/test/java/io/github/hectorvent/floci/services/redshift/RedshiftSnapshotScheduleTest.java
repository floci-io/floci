package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedshiftSnapshotScheduleTest {
    private static final Instant ANCHOR = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void ratesUseTheAnchorAndCoalesceMissedOccurrences() {
        assertTrue(RedshiftSnapshotSchedule.latestDue(List.of("rate(2 hours)"), ANCHOR,
                ANCHOR.plusSeconds(7199)).isEmpty());
        assertEquals(ANCHOR.plusSeconds(7200), RedshiftSnapshotSchedule.latestDue(List.of("rate(2 hours)"),
                ANCHOR, ANCHOR.plusSeconds(7200)).orElseThrow());
        assertEquals(ANCHOR.plusSeconds(21600), RedshiftSnapshotSchedule.latestDue(List.of("rate(2 hours)"),
                ANCHOR, ANCHOR.plusSeconds(25000)).orElseThrow());
    }

    @Test
    void shorthandAndFullCronUseUtcAndAwsWeekdayNumbers() {
        Instant expected = Instant.parse("2026-10-26T09:30:00Z");
        for (String expression : List.of("cron(30 9 MON-FRI)", "cron(30 9 ? * 2-6 *)")) {
            assertEquals(expected, RedshiftSnapshotSchedule.latestDue(List.of(expression), ANCHOR,
                    expected).orElseThrow());
        }
        Instant saturday = Instant.parse("2026-10-31T09:00:00Z");
        assertEquals(saturday, RedshiftSnapshotSchedule.latestDue(List.of("cron(0 9 7)"), ANCHOR,
                saturday).orElseThrow());
    }

    @Test
    void cronRangesStepsAndMultipleDefinitionsChooseLatestOccurrence() {
        Instant now = Instant.parse("2026-10-27T18:15:00Z");
        assertEquals(Instant.parse("2026-10-27T18:00:00Z"), RedshiftSnapshotSchedule.latestDue(
                List.of("cron(0 14-20/1 TUE)", "cron(15 15/2 *)"), ANCHOR, now).orElseThrow());
    }

    @Test
    void fullCronSupportsThePublishedYearRange() {
        Instant future = Instant.parse("2199-01-01T00:00:00Z");
        assertEquals(future, RedshiftSnapshotSchedule.latestDue(List.of("cron(0 0 1 JAN ? 2199)"), ANCHOR,
                future).orElseThrow());
    }

    @Test
    void validatesFrequencyAndFieldRanges() {
        for (String expression : List.of("rate(0 hours)", "rate(25 hours)", "rate(2 days)",
                "cron(60 12 *)", "cron(* * *)", "cron(0 25 *)", "cron(0 12 BAD)",
                "cron(0 12 * * * *)", "rate(99999999999999999999999 days)")) {
            assertEquals("InvalidSchedule", assertThrows(AwsException.class,
                    () -> RedshiftSnapshotSchedule.validate(List.of(expression)), expression).getErrorCode());
        }
        assertThrows(AwsException.class, () -> RedshiftSnapshotSchedule.validate(
                List.of("cron(0 12 *)", "cron(30 12 *)")));
        RedshiftSnapshotSchedule.validate(List.of("cron(0 12 *)", "cron(0 12 *)"));
        RedshiftSnapshotSchedule.validate(List.of("rate(1 day)"));
    }
}
