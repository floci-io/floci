package io.github.hectorvent.floci.services.redshiftserverless;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import io.github.hectorvent.floci.core.common.AwsCronDefinitions;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** UTC schedule rules of a Serverless scheduled action: one-off {@code at} or six-field AWS {@code cron}. */
final class RedshiftServerlessSchedule {
    private static final Duration MINIMUM_INTERVAL = Duration.ofHours(1);
    private static final Duration SPACING_HORIZON = Duration.ofDays(400);
    private static final int MAX_SPACING_CHECKS = 1000;

    private RedshiftServerlessSchedule() { }

    /** Rejects a cron that does not parse or fires more often than hourly; an {@code at} schedule always passes. */
    static void validate(ScheduledAction action) {
        if (action.getScheduleCron() == null || action.getScheduleCron().isBlank()) {
            return;
        }
        ExecutionTime cron = parse(action.getScheduleCron());
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
                throw ServerlessSupport.validation("The cron schedule must be at least one hour apart.");
            }
            previous = next.get();
            cursor = next.get();
        }
    }

    /** The first invocation strictly after {@code after} that is enabled and inside the active window. */
    static Optional<Instant> next(ScheduledAction action, Instant after) {
        if (Boolean.FALSE.equals(action.getEnabled())) {
            return Optional.empty();
        }
        Instant candidate;
        if (action.getScheduleAt() != null) {
            candidate = action.getScheduleAt().isAfter(after) ? action.getScheduleAt() : null;
        } else {
            Instant from = action.getStartTime() != null && action.getStartTime().isAfter(after)
                    ? action.getStartTime().minusNanos(1) : after;
            candidate = nextCron(parse(action.getScheduleCron()), from).orElse(null);
        }
        return Optional.ofNullable(candidate).filter(time -> inWindow(action, time));
    }

    /** The latest invocation in {@code (anchor, now]} that is inside the window; older ones are coalesced away. */
    static Optional<Instant> latestDue(ScheduledAction action, Instant anchor, Instant now) {
        if (Boolean.FALSE.equals(action.getEnabled())) {
            return Optional.empty();
        }
        Instant upper = action.getEndTime() != null && action.getEndTime().isBefore(now) ? action.getEndTime() : now;
        Optional<Instant> latest;
        if (action.getScheduleAt() != null) {
            latest = Optional.of(action.getScheduleAt()).filter(time -> !time.isAfter(upper));
        } else {
            latest = parse(action.getScheduleCron()).lastExecution(upper.atZone(ZoneOffset.UTC).plusNanos(1))
                    .map(value -> value.toInstant());
        }
        return latest.filter(time -> time.isAfter(anchor) && inWindow(action, time));
    }

    private static boolean inWindow(ScheduledAction action, Instant time) {
        return (action.getStartTime() == null || !time.isBefore(action.getStartTime()))
                && (action.getEndTime() == null || !time.isAfter(action.getEndTime()));
    }

    private static Optional<Instant> nextCron(ExecutionTime cron, Instant after) {
        return cron.nextExecution(after.atZone(ZoneOffset.UTC)).map(value -> value.toInstant());
    }

    private static ExecutionTime parse(String expression) {
        String body = expression.trim();
        if (body.startsWith("cron")) {
            body = body.substring(4).trim();
        }
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length() - 1).trim();
        }
        String[] fields = body.split("\\s+");
        if (fields.length != 6) {
            throw ServerlessSupport.validation("The cron schedule needs six fields.");
        }
        if ("?".equals(fields[2]) == "?".equals(fields[4])) {
            throw ServerlessSupport.validation("Use ? in exactly one of day-of-month and day-of-week.");
        }
        try {
            Cron cron = AwsCronDefinitions.newParser().parse("0 " + String.join(" ", fields));
            cron.validate();
            return ExecutionTime.forCron(cron);
        } catch (IllegalArgumentException e) {
            throw ServerlessSupport.validation("The cron schedule is invalid: " + e.getMessage());
        }
    }
}
