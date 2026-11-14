package io.floci.aws.services.redshift;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import io.floci.aws.core.common.AwsCronDefinitions;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.model.ScheduledAction;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** UTC schedule rules of a provisioned scheduled action: one-off {@code at(...)} or six-field AWS {@code cron(...)}. */
final class RedshiftScheduledActionSchedule {

    private static final Duration MINIMUM_INTERVAL = Duration.ofHours(1);
    private static final Duration SPACING_HORIZON = Duration.ofDays(400);
    private static final int MAX_SPACING_CHECKS = 1000;

    private RedshiftScheduledActionSchedule() { }

    /** Rejects an expression that is neither a well-formed {@code at} nor a well-formed {@code cron}. */
    static void validate(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            throw invalid("Schedule is required.");
        }
        String body = schedule.trim();
        if (body.startsWith("at(")) {
            parseAt(body);
        } else if (body.startsWith("cron(")) {
            requireHourlySpacing(parseCron(body));
        } else {
            throw invalid("The schedule must be an at(...) or a cron(...) expression.");
        }
    }

    /** The first invocation strictly after {@code after} that is enabled and inside the active window. */
    static Optional<Instant> next(ScheduledAction action, Instant after) {
        if (!action.isEnabled()) {
            return Optional.empty();
        }
        Instant candidate;
        String schedule = action.getSchedule().trim();
        if (schedule.startsWith("at(")) {
            Instant at = parseAt(schedule);
            candidate = at.isAfter(after) ? at : null;
        } else {
            Instant from = action.getStartTime() != null && action.getStartTime().isAfter(after)
                    ? action.getStartTime().minusNanos(1) : after;
            candidate = nextCron(parseCron(schedule), from).orElse(null);
        }
        return Optional.ofNullable(candidate).filter(time -> inWindow(action, time));
    }

    /** The next {@code count} invocations after {@code after}. */
    static List<Instant> upcoming(ScheduledAction action, Instant after, int count) {
        List<Instant> invocations = new ArrayList<>();
        Instant cursor = after;
        while (invocations.size() < count) {
            Optional<Instant> next = next(action, cursor);
            if (next.isEmpty()) {
                break;
            }
            invocations.add(next.get());
            cursor = next.get();
        }
        return invocations;
    }

    /** The latest invocation in {@code (anchor, now]} that is inside the window; older ones are coalesced away. */
    static Optional<Instant> latestDue(ScheduledAction action, Instant anchor, Instant now) {
        if (!action.isEnabled()) {
            return Optional.empty();
        }
        Instant upper = action.getEndTime() != null && action.getEndTime().isBefore(now) ? action.getEndTime() : now;
        Optional<Instant> latest;
        String schedule = action.getSchedule().trim();
        if (schedule.startsWith("at(")) {
            latest = Optional.of(parseAt(schedule)).filter(time -> !time.isAfter(upper));
        } else {
            latest = parseCron(schedule).lastExecution(upper.atZone(ZoneOffset.UTC).plusNanos(1))
                    .map(value -> value.toInstant());
        }
        return latest.filter(time -> time.isAfter(anchor) && inWindow(action, time));
    }

    /** AWS requires the invocations of a cron schedule to be at least one hour apart. */
    private static void requireHourlySpacing(ExecutionTime cron) {
        Optional<Instant> first = nextCron(cron, Instant.EPOCH);
        if (first.isEmpty()) {
            return;
        }
        Instant cursor = first.get().minusSeconds(1);
        Instant limit = cursor.plus(SPACING_HORIZON);
        Instant previous = null;
        for (int i = 0; i < MAX_SPACING_CHECKS; i++) {
            Optional<Instant> next = nextCron(cron, cursor);
            if (next.isEmpty() || next.get().isAfter(limit)) {
                return;
            }
            if (previous != null && Duration.between(previous, next.get()).compareTo(MINIMUM_INTERVAL) < 0) {
                throw invalid("The cron schedule must be at least one hour apart.");
            }
            previous = next.get();
            cursor = next.get();
        }
    }

    private static boolean inWindow(ScheduledAction action, Instant time) {
        return (action.getStartTime() == null || !time.isBefore(action.getStartTime()))
                && (action.getEndTime() == null || !time.isAfter(action.getEndTime()));
    }

    private static Optional<Instant> nextCron(ExecutionTime cron, Instant after) {
        return cron.nextExecution(after.atZone(ZoneOffset.UTC)).map(value -> value.toInstant());
    }

    private static Instant parseAt(String expression) {
        if (!expression.endsWith(")")) {
            throw invalid("The at(...) schedule is not closed.");
        }
        String timestamp = expression.substring("at(".length(), expression.length() - 1).trim();
        try {
            return LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid("The at(...) schedule needs a UTC timestamp such as at(2026-11-20T10:00:00).");
        }
    }

    private static ExecutionTime parseCron(String expression) {
        if (!expression.endsWith(")")) {
            throw invalid("The cron(...) schedule is not closed.");
        }
        String body = expression.substring("cron(".length(), expression.length() - 1).trim();
        String[] fields = body.split("\\s+");
        if (fields.length != 6) {
            throw invalid("The cron schedule needs six fields.");
        }
        if ("?".equals(fields[2]) == "?".equals(fields[4])) {
            throw invalid("Use ? in exactly one of day-of-month and day-of-week.");
        }
        try {
            Cron cron = AwsCronDefinitions.newParser().parse("0 " + String.join(" ", fields));
            cron.validate();
            return ExecutionTime.forCron(cron);
        } catch (IllegalArgumentException e) {
            throw invalid("The cron schedule is invalid: " + e.getMessage());
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidSchedule", message, 400);
    }
}
