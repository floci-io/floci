package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeUtilTest {

    private final TimeUtil time = new TimeUtil();

    @Test
    void nowISO8601_format() {
        String result = time.nowISO8601();
        assertThat(result, matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z"));
    }

    @Test
    void nowISO8601_currentDate() {
        String result = time.nowISO8601();
        String today = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ZonedDateTime.now(ZoneOffset.UTC));
        assertThat(result, startsWith(today));
    }

    @Test
    void nowEpochSeconds_positive() {
        long result = time.nowEpochSeconds();
        assertThat(result, greaterThan(0L));
    }

    @Test
    void nowEpochSeconds_withinRange() {
        long expected = System.currentTimeMillis() / 1000;
        long result = time.nowEpochSeconds();
        assertThat(Math.abs(result - expected), is(lessThanOrEqualTo(1L)));
    }

    @Test
    void nowEpochMilliSeconds_positive() {
        long result = time.nowEpochMilliSeconds();
        assertThat(result, greaterThan(0L));
    }

    @Test
    void nowEpochMilliSeconds_withinRange() {
        long expected = System.currentTimeMillis();
        long result = time.nowEpochMilliSeconds();
        assertThat(Math.abs(result - expected), is(lessThanOrEqualTo(100L)));
    }

    @Test
    void nowFormatted_utc() {
        String result = time.nowFormatted("yyyy-MM-dd");
        String today = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ZonedDateTime.now(ZoneOffset.UTC));
        assertThat(result, is(today));
    }

    @Test
    void nowFormatted_withOffset() {
        String result = time.nowFormatted("HH", "+05:30");
        assertThat(result, matchesPattern("\\d{2}"));
    }

    @Test
    void nowFormatted_invalidFormat() {
        assertThrows(Exception.class, () -> time.nowFormatted("INVALID"));
    }

    @Test
    void parseISO8601ToEpochMilliSeconds_valid() {
        long result = time.parseISO8601ToEpochMilliSeconds("2024-01-15T12:00:00Z");
        Instant instant = Instant.parse("2024-01-15T12:00:00Z");
        assertThat(result, is(instant.toEpochMilli()));
    }

    @Test
    void parseISO8601ToEpochMilliSeconds_epoch0() {
        long result = time.parseISO8601ToEpochMilliSeconds("1970-01-01T00:00:00Z");
        assertThat(result, is(0L));
    }

    @Test
    void parseISO8601ToEpochMilliSeconds_invalid() {
        assertThrows(Exception.class, () -> time.parseISO8601ToEpochMilliSeconds("not-a-date"));
    }

    @Test
    void parseFormattedToEpochMilliSeconds_valid() {
        long result = time.parseFormattedToEpochMilliSeconds("2024-01-15 12:00:00", "yyyy-MM-dd HH:mm:ss");
        Instant expected = Instant.parse("2024-01-15T12:00:00Z");
        assertThat(result, is(expected.toEpochMilli()));
    }

    @Test
    void parseFormattedToEpochMilliSeconds_withTimezone() {
        long result = time.parseFormattedToEpochMilliSeconds("2024-01-15 12:00:00", "yyyy-MM-dd HH:mm:ss", "+00:00");
        Instant expected = Instant.parse("2024-01-15T12:00:00Z");
        assertThat(result, is(expected.toEpochMilli()));
    }

    @Test
    void parseFormattedToEpochMilliSeconds_invalid() {
        assertThrows(Exception.class, () -> time.parseFormattedToEpochMilliSeconds("invalid", "yyyy-MM-dd"));
    }

    @Test
    void epochMilliSecondsToISO8601_valid() {
        Instant instant = Instant.parse("2024-01-15T12:00:00Z");
        String result = time.epochMilliSecondsToISO8601(instant.toEpochMilli());
        assertThat(result, is("2024-01-15T12:00:00.000Z"));
    }

    @Test
    void epochMilliSecondsToISO8601_epoch0() {
        String result = time.epochMilliSecondsToISO8601(0L);
        assertThat(result, is("1970-01-01T00:00:00.000Z"));
    }

    @Test
    void epochMilliSecondsToSeconds_valid() {
        long result = time.epochMilliSecondsToSeconds(1705310400000L);
        assertThat(result, is(1705310400L));
    }

    @Test
    void epochMilliSecondsToSeconds_epoch0() {
        long result = time.epochMilliSecondsToSeconds(0L);
        assertThat(result, is(0L));
    }

    @Test
    void epochMilliSecondsToFormatted_valid() {
        Instant instant = Instant.parse("2024-01-15T12:00:00Z");
        String result = time.epochMilliSecondsToFormatted(instant.toEpochMilli(), "yyyy-MM-dd");
        assertThat(result, is("2024-01-15"));
    }

    @Test
    void epochMilliSecondsToFormatted_withOffset() {
        Instant instant = Instant.parse("2024-01-15T12:00:00Z");
        String result = time.epochMilliSecondsToFormatted(instant.toEpochMilli(), "yyyy-MM-dd", "+00:00");
        assertThat(result, is("2024-01-15"));
    }
}
