package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Cost Explorer {@code TimePeriod} into AWS-shaped granularity buckets.
 * <p>
 * AWS truncates {@code Start}/{@code End} to whole-day boundaries for DAILY
 * and HOURLY, and to the first day of the month for MONTHLY. The window is
 * half-open: {@code Start} inclusive, {@code End} exclusive.
 */
final class TimeBucketing {

    enum Granularity { HOURLY, DAILY, MONTHLY }

    static Granularity parseGranularity(String value) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Granularity' failed to satisfy constraint: Member must not be null.", 400);
        }
        return switch (value) {
            case "HOURLY" -> Granularity.HOURLY;
            case "DAILY" -> Granularity.DAILY;
            case "MONTHLY" -> Granularity.MONTHLY;
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + value + "' at 'Granularity' failed to satisfy constraint: Member must satisfy enum value set: [DAILY, MONTHLY, HOURLY]", 400);
        };
    }

    static Instant parseDate(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field + "' failed to satisfy constraint: Member must not be null.", 400);
        }
        // AWS accepts "YYYY-MM-DD" (most common) and ISO-8601 timestamps.
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new AwsException("ValidationException",
                    field + " must be a YYYY-MM-DD date or ISO-8601 timestamp.", 400);
        }
    }

    /** A half-open [start, end) bucket aligned to the requested granularity. */
    record Bucket(Instant start, Instant end) {}

    static List<Bucket> split(Instant start, Instant end, Granularity granularity) {
        if (!end.isAfter(start)) {
            throw new AwsException("DataUnavailableException",
                    "End date must be after Start date.", 400);
        }
        // Truncate Start down and End up to the granularity boundary so every
        // emitted bucket aligns to the period AWS would return.
        Instant alignedStart = floor(start, granularity);
        Instant alignedEnd = ceil(end, granularity);
        return switch (granularity) {
            case HOURLY -> splitHourly(alignedStart, alignedEnd);
            case DAILY -> splitDaily(alignedStart, alignedEnd);
            case MONTHLY -> splitMonthly(alignedStart, alignedEnd);
        };
    }

    private static Instant floor(Instant instant, Granularity granularity) {
        return switch (granularity) {
            case HOURLY -> instant.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            case DAILY -> instant.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            case MONTHLY -> YearMonth.from(instant.atOffset(ZoneOffset.UTC))
                    .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    private static Instant ceil(Instant instant, Granularity granularity) {
        Instant floored = floor(instant, granularity);
        if (floored.equals(instant)) {
            return instant;
        }
        return switch (granularity) {
            case HOURLY -> floored.plusSeconds(3600);
            case DAILY -> floored.plus(java.time.Duration.ofDays(1));
            case MONTHLY -> YearMonth.from(floored.atOffset(ZoneOffset.UTC))
                    .plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    private static List<Bucket> splitHourly(Instant start, Instant end) {
        List<Bucket> out = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plusSeconds(3600);
            out.add(new Bucket(cursor, next.isAfter(end) ? end : next));
            cursor = next;
        }
        return out;
    }

    private static List<Bucket> splitDaily(Instant start, Instant end) {
        List<Bucket> out = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(java.time.Duration.ofDays(1));
            out.add(new Bucket(cursor, next.isAfter(end) ? end : next));
            cursor = next;
        }
        return out;
    }

    private static List<Bucket> splitMonthly(Instant start, Instant end) {
        List<Bucket> out = new ArrayList<>();
        YearMonth ym = YearMonth.from(start.atOffset(ZoneOffset.UTC));
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            ym = ym.plusMonths(1);
            Instant next = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            out.add(new Bucket(cursor, next.isAfter(end) ? end : next));
            cursor = next;
        }
        return out;
    }

    private TimeBucketing() {}
}
