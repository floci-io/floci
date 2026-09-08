package io.github.hectorvent.floci.services.eventbridge;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleExpressionParserTest {

    // ──────────────────────────── Expression Type Detection ────────────────────────────

    @Test
    void isRateExpression() {
        assertTrue(ScheduleExpressionParser.isRateExpression("rate(5 minutes)"));
        assertTrue(ScheduleExpressionParser.isRateExpression("rate(1 hour)"));
        assertTrue(ScheduleExpressionParser.isRateExpression("RATE(5 MINUTES)"));
        assertFalse(ScheduleExpressionParser.isRateExpression("cron(0 10 * * ? *)"));
        assertFalse(ScheduleExpressionParser.isRateExpression("invalid"));
        assertFalse(ScheduleExpressionParser.isRateExpression(null));
    }

    @Test
    void isCronExpression() {
        assertTrue(ScheduleExpressionParser.isCronExpression("cron(0 10 * * ? *)"));
        assertTrue(ScheduleExpressionParser.isCronExpression("CRON(0 10 * * ? *)"));
        assertFalse(ScheduleExpressionParser.isCronExpression("rate(5 minutes)"));
        assertFalse(ScheduleExpressionParser.isCronExpression("invalid"));
        assertFalse(ScheduleExpressionParser.isCronExpression(null));
    }

    // ──────────────────────────── Rate Expressions ────────────────────────────

    @Test
    void parseRateMinutes() {
        assertEquals(300000, ScheduleExpressionParser.parseRateToMillis("rate(5 minutes)"));
        assertEquals(60000, ScheduleExpressionParser.parseRateToMillis("rate(1 minute)"));
        assertEquals(120000, ScheduleExpressionParser.parseRateToMillis("rate(2 minutes)"));
    }

    @Test
    void parseRateHours() {
        assertEquals(3600000, ScheduleExpressionParser.parseRateToMillis("rate(1 hour)"));
        assertEquals(7200000, ScheduleExpressionParser.parseRateToMillis("rate(2 hours)"));
    }

    @Test
    void parseRateDays() {
        assertEquals(86400000, ScheduleExpressionParser.parseRateToMillis("rate(1 day)"));
        assertEquals(172800000, ScheduleExpressionParser.parseRateToMillis("rate(2 days)"));
    }

    @Test
    void parseRateWeeks() {
        assertEquals(604800000, ScheduleExpressionParser.parseRateToMillis("rate(1 week)"));
        assertEquals(1209600000, ScheduleExpressionParser.parseRateToMillis("rate(2 weeks)"));
    }

    @Test
    void parseRateAcceptsSingularAndPluralUnits() {
        assertEquals(60000, ScheduleExpressionParser.parseRateToMillis("rate(1 minute)"));
        assertEquals(60000, ScheduleExpressionParser.parseRateToMillis("rate(1 minutes)"));
        assertEquals(120000, ScheduleExpressionParser.parseRateToMillis("rate(2 minute)"));
        assertEquals(120000, ScheduleExpressionParser.parseRateToMillis("rate(2 minutes)"));
    }

    @Test
    void parseRateRejectsZeroValue() {
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis("rate(0 minutes)"));
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis("rate(0 minute)"));
    }

    @Test
    void parseRateCaseInsensitive() {
        assertEquals(300000, ScheduleExpressionParser.parseRateToMillis("RATE(5 MINUTES)"));
        assertEquals(300000, ScheduleExpressionParser.parseRateToMillis("Rate(5 Minutes)"));
    }

    @Test
    void parseRateWithSpaces() {
        assertEquals(300000, ScheduleExpressionParser.parseRateToMillis("rate( 5 minutes )"));
    }

    @Test
    void parseRateInvalidFormatThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis("rate(5)"));
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis("rate(minutes)"));
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis("cron(0 10 * * ? *)"));
    }

    @Test
    void parseRateNullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.parseRateToMillis(null));
    }

    // ──────────────────────────── Cron Expressions ────────────────────────────

    @Test
    void getNextFireTimeDailyCron() {
        ZonedDateTime from = ZonedDateTime.parse("2026-03-22T08:00:00Z");
        ZonedDateTime next = ScheduleExpressionParser.getNextFireTime("cron(0 10 * * ? *)", from);

        assertNotNull(next);
        assertEquals(10, next.getHour());
        assertEquals(0, next.getMinute());
    }

    @Test
    void getNextFireTimeEvery15Minutes() {
        ZonedDateTime from = ZonedDateTime.parse("2026-03-22T10:00:00Z");
        ZonedDateTime next = ScheduleExpressionParser.getNextFireTime("cron(0/15 * * * ? *)", from);

        assertNotNull(next);
        assertEquals(10, next.getHour());
        assertEquals(15, next.getMinute());
    }

    @Test
    void getNextFireTimeWeekdays() {
        // AWS day-of-week is 1-7 = SUN-SAT, so Monday to Friday is 2-6.
        ZonedDateTime from = ZonedDateTime.parse("2026-03-23T08:00:00Z");
        ZonedDateTime next = ScheduleExpressionParser.getNextFireTime("cron(0 9-17 * * 2-6 *)", from);

        assertNotNull(next);
        assertTrue(next.getHour() >= 9 && next.getHour() <= 17);
        assertTrue(next.getDayOfWeek().getValue() >= DayOfWeek.MONDAY.getValue()
                        && next.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue(),
                "expected a weekday but got " + next);
    }

    @Test
    void getNextFireTimeFirstMondayOfMonth() {
        ZonedDateTime from = ZonedDateTime.parse("2026-03-01T02:00:00Z");
        ZonedDateTime next = ScheduleExpressionParser.getNextFireTime("cron(30 2 ? * 2#1 *)", from);

        assertEquals(ZonedDateTime.parse("2026-03-02T02:30:00Z"), next);
        assertEquals(DayOfWeek.MONDAY, next.getDayOfWeek());
    }

    // ──────────────────────────── Day-of-week numbering (AWS 1-7 = SUN-SAT) ────────────────────────────

    /** 2026-03-01 is a Sunday, so it doubles as the day the "from" instant lands on. */
    private static final ZonedDateTime SUNDAY_2026_03_01_02_00 = ZonedDateTime.parse("2026-03-01T02:00:00Z");

    @Test
    void dayOfWeekOneIsSunday() {
        assertEquals(ZonedDateTime.parse("2026-03-01T12:00:00Z"),
                ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * 1 *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void dayOfWeekTwoIsMonday() {
        assertEquals(ZonedDateTime.parse("2026-03-02T12:00:00Z"),
                ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * 2 *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void dayOfWeekSevenIsSaturdayAndIsAccepted() {
        assertEquals(ZonedDateTime.parse("2026-03-07T12:00:00Z"),
                ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * 7 *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void dayOfWeekNamesAgreeWithTheirNumbers() {
        assertEquals(
                ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * 2 *)", SUNDAY_2026_03_01_02_00),
                ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * MON *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void lastWeekdayOfMonthUsesAwsNumbering() {
        // AWS documents cron(15 10 ? * 6L 2022-2023) as the last Friday of the month.
        // The last Friday of March 2026 is the 27th.
        assertEquals(ZonedDateTime.parse("2026-03-27T10:15:00Z"),
                ScheduleExpressionParser.getNextFireTime("cron(15 10 ? * 6L *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void dayOfWeekZeroIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleExpressionParser.getNextFireTime("cron(0 12 ? * 0 *)", SUNDAY_2026_03_01_02_00));
    }

    @Test
    void getNextFireTimeInvalidCronThrows() {
        assertThrows(Exception.class, () ->
                ScheduleExpressionParser.getNextFireTime("cron(invalid)", ZonedDateTime.now()));
    }

    @Test
    void getNextFireTimeNotCronExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.getNextFireTime("rate(5 minutes)", ZonedDateTime.now()));
    }

    @Test
    void getNextFireTimeRejects5FieldCron() {
        assertThrows(IllegalArgumentException.class, () ->
                ScheduleExpressionParser.getNextFireTime("cron(0 10 * * *)", ZonedDateTime.now()));
    }

    // ──────────────────────────── millisUntilNextFire ────────────────────────────

    @Test
    void millisUntilNextFireCron() {
        ZonedDateTime from = ZonedDateTime.parse("2026-03-22T10:00:00Z");
        long delay = ScheduleExpressionParser.millisUntilNextFire("cron(0/15 * * * ? *)", from);

        assertTrue(delay > 0);
        assertTrue(delay <= 15 * 60 * 1000);
    }

    @Test
    void millisUntilNextFireCronEveryMinute() {
        ZonedDateTime from = ZonedDateTime.parse("2026-03-22T10:00:30Z");
        long delay = ScheduleExpressionParser.millisUntilNextFire("cron(0 * * * ? *)", from);

        assertTrue(delay >= 1000);
    }
}
