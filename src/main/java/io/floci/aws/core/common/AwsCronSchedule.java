package io.floci.aws.core.common;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Function;

/**
 * The UTC rules the scheduled actions and snapshot schedules of Redshift and Redshift Serverless share for a
 * six-field AWS {@code cron}: how the fields are read, that two invocations are at least an hour apart, which
 * invocation comes next or is the latest due, and how an active window narrows them. The error each service
 * raises for a bad expression differs, so the callers pass the exception to throw.
 */
public final class AwsCronSchedule {

    private static final Duration MINIMUM_INTERVAL = Duration.ofHours(1);
    private static final Duration SPACING_HORIZON = Duration.ofDays(400);
    private static final int MAX_SPACING_CHECKS = 1000;

    private AwsCronSchedule() {
    }

    /** Reads the six fields of an AWS cron, which put {@code ?} in exactly one of day-of-month and day-of-week. */
    public static <E extends RuntimeException> ExecutionTime parse(String[] fields, Function<String, E> invalid) {
        if (fields.length != 6) {
            throw invalid.apply("The cron schedule needs six fields.");
        }
        if ("?".equals(fields[2]) == "?".equals(fields[4])) {
            throw invalid.apply("Use ? in exactly one of day-of-month and day-of-week.");
        }
        try {
            Cron cron = AwsCronDefinitions.newParser().parse("0 " + String.join(" ", fields));
            cron.validate();
            return ExecutionTime.forCron(cron);
        } catch (IllegalArgumentException e) {
            throw invalid.apply("The cron schedule is invalid: " + e.getMessage());
        }
    }

    /** AWS requires the invocations of a cron schedule to be at least one hour apart. */
    public static <E extends RuntimeException> void requireHourlySpacing(ExecutionTime cron,
                                                                         Function<String, E> invalid) {
        Optional<Instant> first = next(cron, Instant.EPOCH);
        if (first.isEmpty()) {
            return;
        }
        Instant cursor = first.get().minusSeconds(1);
        Instant limit = cursor.plus(SPACING_HORIZON);
        Instant previous = null;
        for (int i = 0; i < MAX_SPACING_CHECKS; i++) {
            Optional<Instant> next = next(cron, cursor);
            if (next.isEmpty() || next.get().isAfter(limit)) {
                return;
            }
            if (previous != null && Duration.between(previous, next.get()).compareTo(MINIMUM_INTERVAL) < 0) {
                throw invalid.apply("The cron schedule must be at least one hour apart.");
            }
            previous = next.get();
            cursor = next.get();
        }
    }

    /** The first invocation strictly after {@code after}. */
    public static Optional<Instant> next(ExecutionTime cron, Instant after) {
        return cron.nextExecution(after.atZone(ZoneOffset.UTC)).map(value -> value.toInstant());
    }

    /** The latest invocation at or before {@code upper}. */
    public static Optional<Instant> latest(ExecutionTime cron, Instant upper) {
        return cron.lastExecution(upper.atZone(ZoneOffset.UTC).plusNanos(1)).map(value -> value.toInstant());
    }

    /** Where to look for the next invocation after {@code after}, which a window that has not opened moves up. */
    public static Instant searchFrom(Instant after, Instant windowStart) {
        return windowStart != null && windowStart.isAfter(after) ? windowStart.minusNanos(1) : after;
    }

    /** The last moment worth looking at up to {@code now}, which a window that has closed moves back. */
    public static Instant searchUpTo(Instant now, Instant windowEnd) {
        return windowEnd != null && windowEnd.isBefore(now) ? windowEnd : now;
    }

    /** Whether {@code time} is inside the window, whose ends are inclusive and either of which may be open. */
    public static boolean inWindow(Instant time, Instant windowStart, Instant windowEnd) {
        return (windowStart == null || !time.isBefore(windowStart))
                && (windowEnd == null || !time.isAfter(windowEnd));
    }
}
