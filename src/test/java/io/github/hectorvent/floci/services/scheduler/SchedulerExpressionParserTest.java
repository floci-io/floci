package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.services.scheduler.SchedulerExpressionParser.Kind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerExpressionParserTest {

    @Test
    void classifyAtExpression() {
        assertEquals(Kind.AT, SchedulerExpressionParser.classify("at(2026-04-21T09:17:54)"));
        assertEquals(Kind.AT, SchedulerExpressionParser.classify("AT(2026-04-21T09:17:54)"));
    }

    @Test
    void classifyRateExpression() {
        assertEquals(Kind.RATE, SchedulerExpressionParser.classify("rate(5 minutes)"));
        assertEquals(Kind.RATE, SchedulerExpressionParser.classify("rate(1 hour)"));
    }

    @Test
    void classifyCronExpression() {
        assertEquals(Kind.CRON, SchedulerExpressionParser.classify("cron(0 10 * * ? *)"));
    }

    @Test
    void classifyRejectsUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.classify("every 5 minutes"));
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.classify(null));
    }

    @Test
    void parseAtInUtc() {
        Instant expected = ZonedDateTime.of(2026, 4, 21, 9, 17, 54, 0, ZoneOffset.UTC).toInstant();
        assertEquals(expected, SchedulerExpressionParser.parseAt("at(2026-04-21T09:17:54)", null));
        assertEquals(expected, SchedulerExpressionParser.parseAt("at(2026-04-21T09:17:54)", "UTC"));
    }

    @Test
    void parseAtInTimezoneShiftsInstant() {
        Instant utc = SchedulerExpressionParser.parseAt("at(2026-04-21T09:17:54)", "UTC");
        Instant berlin = SchedulerExpressionParser.parseAt("at(2026-04-21T09:17:54)", "Europe/Berlin");
        assertTrue(berlin.isBefore(utc),
                "09:17 Europe/Berlin should be an earlier instant than 09:17 UTC");
    }

    @Test
    void parseAtRejectsMalformed() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.parseAt("at(not-a-date)", null));
    }

    @Test
    void parseRateMillis() {
        assertEquals(300_000L, SchedulerExpressionParser.parseRateMillis("rate(5 minutes)"));
        assertEquals(3_600_000L, SchedulerExpressionParser.parseRateMillis("rate(1 hour)"));
        assertEquals(86_400_000L, SchedulerExpressionParser.parseRateMillis("rate(1 day)"));
        assertEquals(604_800_000L, SchedulerExpressionParser.parseRateMillis("rate(1 week)"));
    }

    @Test
    void parseRateRejectsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.parseRateMillis("rate(0 minutes)"));
    }

    @Test
    void nextCronFireComputesFutureInstant() {
        Instant from = ZonedDateTime.of(2026, 4, 21, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant next = SchedulerExpressionParser.nextCronFire("cron(30 10 * * ? *)", from, null);
        ZonedDateTime asUtc = next.atZone(ZoneOffset.UTC);
        assertEquals(10, asUtc.getHour());
        assertEquals(30, asUtc.getMinute());
        assertTrue(next.isAfter(from));
    }

    @Test
    void nextCronFireRespectsTimezone() {
        Instant from = ZonedDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant nextUtc = SchedulerExpressionParser.nextCronFire("cron(0 10 * * ? *)", from, "UTC");
        Instant nextBerlin = SchedulerExpressionParser.nextCronFire("cron(0 10 * * ? *)", from, "Europe/Berlin");
        assertNotEquals(nextUtc, nextBerlin,
                "10:00 UTC and 10:00 Europe/Berlin are different absolute instants");
    }

    @Test
    void nextCronFireRejectsWrongFieldCount() {
        Instant from = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.nextCronFire("cron(0 10 * * *)", from, null));
    }

    // Day-of-week numbering: AWS is 1-7 = SUN-SAT.

    /** 2026-03-01 is a Sunday, so it doubles as the day the "from" instant lands on. */
    private static final Instant SUNDAY_2026_03_01_02_00 =
            ZonedDateTime.of(2026, 3, 1, 2, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private static Instant utc(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant();
    }

    @Test
    void dayOfWeekOneIsSunday() {
        assertEquals(utc(1, 12, 0),
                SchedulerExpressionParser.nextCronFire("cron(0 12 ? * 1 *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void dayOfWeekTwoIsMonday() {
        assertEquals(utc(2, 12, 0),
                SchedulerExpressionParser.nextCronFire("cron(0 12 ? * 2 *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void dayOfWeekSevenIsSaturdayAndIsAccepted() {
        assertEquals(utc(7, 12, 0),
                SchedulerExpressionParser.nextCronFire("cron(0 12 ? * 7 *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void dayOfWeekNamesAgreeWithTheirNumbers() {
        assertEquals(
                SchedulerExpressionParser.nextCronFire("cron(0 12 ? * 2 *)", SUNDAY_2026_03_01_02_00, "UTC"),
                SchedulerExpressionParser.nextCronFire("cron(0 12 ? * MON *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void hashSelectsTheNthWeekdayOfTheMonth() {
        // 2#1 is the first Monday of March 2026, which is the 2nd.
        assertEquals(utc(2, 2, 30),
                SchedulerExpressionParser.nextCronFire("cron(30 2 ? * 2#1 *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void lastWeekdayOfMonthUsesAwsNumbering() {
        // AWS documents cron(15 10 ? * 6L 2022-2023) as the last Friday of the month.
        // The last Friday of March 2026 is the 27th.
        assertEquals(utc(27, 10, 15),
                SchedulerExpressionParser.nextCronFire("cron(15 10 ? * 6L *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }

    @Test
    void dayOfWeekZeroIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.nextCronFire("cron(0 12 ? * 0 *)", SUNDAY_2026_03_01_02_00, "UTC"));
    }
}
