package io.github.hectorvent.floci.services.appsync.graphql.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public class TimeUtil {

    private static final DateTimeFormatter ISO_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
            .appendOffsetId()
            .toFormatter()
            .withZone(ZoneOffset.UTC);

    public String nowISO8601() {
        return ISO_FORMATTER.format(Instant.now());
    }

    public long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    public long nowEpochMilliSeconds() {
        return System.currentTimeMillis();
    }

    public String nowFormatted(String format) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        return formatter.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    public String nowFormatted(String format, String offset) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        ZoneOffset zoneOffset = ZoneOffset.of(offset);
        return formatter.format(ZonedDateTime.now(zoneOffset));
    }

    public long parseFormattedToEpochMilliSeconds(String timestamp, String format) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        var parsed = java.time.LocalDateTime.parse(timestamp, formatter);
        return parsed.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public long parseFormattedToEpochMilliSeconds(String timestamp, String format, String timezone) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        ZoneOffset zoneOffset = ZoneOffset.of(timezone);
        var parsed = java.time.LocalDateTime.parse(timestamp, formatter);
        return parsed.toInstant(zoneOffset).toEpochMilli();
    }

    public long parseISO8601ToEpochMilliSeconds(String timestamp) {
        return Instant.parse(timestamp).toEpochMilli();
    }

    public long epochMilliSecondsToSeconds(long epochMilliSeconds) {
        return epochMilliSeconds / 1000;
    }

    public String epochMilliSecondsToISO8601(long epochMilliSeconds) {
        return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMilliSeconds));
    }

    public String epochMilliSecondsToFormatted(long epochMilliSeconds, String format) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMilliSeconds).atZone(ZoneOffset.UTC);
        return formatter.format(zdt);
    }

    public String epochMilliSecondsToFormatted(long epochMilliSeconds, String format, String offset) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        ZoneOffset zoneOffset = ZoneOffset.of(offset);
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMilliSeconds).atZone(zoneOffset);
        return formatter.format(zdt);
    }
}
