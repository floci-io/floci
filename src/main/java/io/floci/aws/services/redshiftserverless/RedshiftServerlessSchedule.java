package io.floci.aws.services.redshiftserverless;

import com.cronutils.model.time.ExecutionTime;
import io.floci.aws.core.common.AwsCronSchedule;
import io.floci.aws.services.redshiftserverless.model.ScheduledAction;

import java.time.Instant;
import java.util.Optional;

/** UTC schedule rules of a Serverless scheduled action: one-off {@code at} or six-field AWS {@code cron}. */
final class RedshiftServerlessSchedule {
    private RedshiftServerlessSchedule() { }

    /** Rejects a cron that does not parse or fires more often than hourly; an {@code at} schedule always passes. */
    static void validate(ScheduledAction action) {
        if (action.getScheduleCron() == null || action.getScheduleCron().isBlank()) {
            return;
        }
        AwsCronSchedule.requireHourlySpacing(parse(action.getScheduleCron()), ServerlessSupport::validation);
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
            candidate = AwsCronSchedule.next(parse(action.getScheduleCron()),
                    AwsCronSchedule.searchFrom(after, action.getStartTime())).orElse(null);
        }
        return Optional.ofNullable(candidate).filter(time -> inWindow(action, time));
    }

    /** The latest invocation in {@code (anchor, now]} that is inside the window; older ones are coalesced away. */
    static Optional<Instant> latestDue(ScheduledAction action, Instant anchor, Instant now) {
        if (Boolean.FALSE.equals(action.getEnabled())) {
            return Optional.empty();
        }
        Instant upper = AwsCronSchedule.searchUpTo(now, action.getEndTime());
        Optional<Instant> latest;
        if (action.getScheduleAt() != null) {
            latest = Optional.of(action.getScheduleAt()).filter(time -> !time.isAfter(upper));
        } else {
            latest = AwsCronSchedule.latest(parse(action.getScheduleCron()), upper);
        }
        return latest.filter(time -> time.isAfter(anchor) && inWindow(action, time));
    }

    private static boolean inWindow(ScheduledAction action, Instant time) {
        return AwsCronSchedule.inWindow(time, action.getStartTime(), action.getEndTime());
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
        return AwsCronSchedule.parse(fields, ServerlessSupport::validation);
    }
}
