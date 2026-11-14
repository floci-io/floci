package io.floci.aws.services.redshift;

import com.cronutils.model.time.ExecutionTime;
import io.floci.aws.core.common.AwsCronSchedule;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.model.ScheduledAction;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** UTC schedule rules of a provisioned scheduled action: one-off {@code at(...)} or six-field AWS {@code cron(...)}. */
final class RedshiftScheduledActionSchedule {

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
            AwsCronSchedule.requireHourlySpacing(parseCron(body), RedshiftScheduledActionSchedule::invalid);
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
            candidate = AwsCronSchedule.next(parseCron(schedule),
                    AwsCronSchedule.searchFrom(after, action.getStartTime())).orElse(null);
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
        Instant upper = AwsCronSchedule.searchUpTo(now, action.getEndTime());
        Optional<Instant> latest;
        String schedule = action.getSchedule().trim();
        if (schedule.startsWith("at(")) {
            latest = Optional.of(parseAt(schedule)).filter(time -> !time.isAfter(upper));
        } else {
            latest = AwsCronSchedule.latest(parseCron(schedule), upper);
        }
        return latest.filter(time -> time.isAfter(anchor) && inWindow(action, time));
    }

    private static boolean inWindow(ScheduledAction action, Instant time) {
        return AwsCronSchedule.inWindow(time, action.getStartTime(), action.getEndTime());
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
        return AwsCronSchedule.parse(fields, RedshiftScheduledActionSchedule::invalid);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidSchedule", message, 400);
    }
}
