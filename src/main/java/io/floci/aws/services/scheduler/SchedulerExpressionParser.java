package io.floci.aws.services.scheduler;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.floci.aws.core.common.AwsCronDefinitions;
import io.floci.aws.core.common.AwsRateExpressions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AWS EventBridge Scheduler schedule expressions and computes next fire times.
 *
 * Supported forms (matches the AWS API):
 * <ul>
 *   <li>{@code at(YYYY-MM-DDTHH:mm:ss)}: one-time fire at the given instant
 *       (interpreted in {@code scheduleExpressionTimezone}, default UTC).</li>
 *   <li>{@code rate(N unit)}: repeating fire every N minutes/hours/days;
 *       previously persisted week-based schedules remain readable.</li>
 *   <li>{@code cron(fields)}: six-field AWS EventBridge cron (minute hour DOM month DOW year).</li>
 * </ul>
 */
public final class SchedulerExpressionParser {

    private static final Pattern AT_PATTERN = Pattern.compile(
            "^at\\(\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "^rate\\(\\s*(\\d+)\\s+(minutes?|hours?|days?|weeks?)\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^cron\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter AT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final CronParser CRON_PARSER = AwsCronDefinitions.newParser();

    public enum Kind { AT, RATE, CRON }

    private SchedulerExpressionParser() {}

    public static Kind classify(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("Schedule expression is null");
        }
        String trimmed = expression.trim();
        if (AT_PATTERN.matcher(trimmed).matches()) return Kind.AT;
        if (RATE_PATTERN.matcher(trimmed).matches()) return Kind.RATE;
        if (CRON_PATTERN.matcher(trimmed).matches()) return Kind.CRON;
        throw new IllegalArgumentException("Unsupported schedule expression: " + expression);
    }

    /** Checks the complete expression before a schedule is persisted. */
    public static void validate(String expression, String timezone) {
        switch (classify(expression)) {
            case AT -> parseAt(expression, timezone);
            case RATE -> {
                Rate rate = parseRate(expression);
                AwsRateExpressions.validate(rate.value(), rate.unit());
                rate.toMillis();
            }
            case CRON -> {
                parseCron(expression);
                resolveZone(timezone);
            }
        }
    }

    /**
     * Parses an {@code at(...)} expression and returns the fire instant.
     * The timestamp is interpreted in {@code timezone} (default UTC).
     */
    public static Instant parseAt(String expression, String timezone) {
        Matcher m = AT_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid at() expression: " + expression);
        }
        LocalDateTime local = LocalDateTime.parse(m.group(1), AT_FORMATTER);
        ZoneId zone = resolveZone(timezone);
        return local.atZone(zone).toInstant();
    }

    /**
     * Parses a {@code rate(...)} expression and returns the interval in milliseconds.
     */
    public static long parseRateMillis(String expression) {
        return parseRate(expression).toMillis();
    }

    private static Rate parseRate(String expression) {
        Matcher m = RATE_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid rate() expression: " + expression);
        }
        long value = Long.parseLong(m.group(1));
        AwsRateExpressions.requirePositiveValue(value);
        return new Rate(value, m.group(2).toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the next fire instant at or after {@code from} for a cron expression
     * evaluated in {@code timezone} (default UTC).
     */
    public static Instant nextCronFire(String expression, Instant from, String timezone) {
        Cron cron = parseCron(expression);
        ExecutionTime exec = ExecutionTime.forCron(cron);

        ZoneId zone = resolveZone(timezone);
        ZonedDateTime zdt = from.atZone(zone);
        return exec.nextExecution(zdt)
                .map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalStateException(
                        "No next fire time for cron expression: " + expression));
    }

    private static Cron parseCron(String expression) {
        Matcher m = CRON_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid cron() expression: " + expression);
        }
        String cronFields = m.group(1).trim();
        String[] fields = cronFields.split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                    "AWS EventBridge cron expressions require 6 fields (minute hour day-of-month month day-of-week year), got "
                            + fields.length + ": " + cronFields);
        }
        Cron cron = CRON_PARSER.parse("0 " + cronFields);
        cron.validate();
        return cron;
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        return ZoneId.of(timezone);
    }

    private record Rate(long value, String unit) {
        long toMillis() {
            return switch (unit) {
                case "minute", "minutes" -> Math.multiplyExact(value, 60_000L);
                case "hour", "hours" -> Math.multiplyExact(value, 3_600_000L);
                case "day", "days" -> Math.multiplyExact(value, 86_400_000L);
                case "week", "weeks" -> Math.multiplyExact(value, 604_800_000L);
                default -> throw new IllegalArgumentException("Unknown rate unit: " + unit);
            };
        }
    }
}
