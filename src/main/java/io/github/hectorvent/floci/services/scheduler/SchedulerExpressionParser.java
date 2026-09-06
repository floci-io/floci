package io.github.hectorvent.floci.services.scheduler;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AWS EventBridge Scheduler schedule expressions and computes next fire times.
 *
 * Supported forms (matches the AWS API):
 * <ul>
 *   <li>{@code at(YYYY-MM-DDTHH:mm:ss)} — one-time fire at the given instant
 *       (interpreted in {@code scheduleExpressionTimezone}, default UTC).</li>
 *   <li>{@code rate(N unit)} — repeating fire every N minutes/hours/days/weeks.</li>
 *   <li>{@code cron(fields)} — six-field AWS EventBridge cron (minute hour DOM month DOW year).</li>
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
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final CronParser CRON_PARSER;

    static {
        CronDefinition definition = CronDefinitionBuilder.defineCron()
                .withSeconds().and()
                .withMinutes().and()
                .withHours().and()
                .withDayOfMonth().supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withMonth().and()
                // AWS numbers day-of-week 1-7 as SUN-SAT, not the Unix 0-6 SUN-SAT that cron-utils
                // defaults to, so Monday is 2. Without this every numeric day fires one day late and
                // 7 (Saturday) does not parse at all.
                .withDayOfWeek().withValidRange(1, 7).withMondayDoWValue(2)
                .supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withYear().optional().and()
                .instance();
        CRON_PARSER = new CronParser(definition);
    }

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
        Matcher m = RATE_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid rate() expression: " + expression);
        }
        int value = Integer.parseInt(m.group(1));
        if (value < 1) {
            throw new IllegalArgumentException("Rate value must be >= 1, got: " + value);
        }
        String unit = m.group(2).toLowerCase();
        return switch (unit) {
            case "minute", "minutes" -> value * 60_000L;
            case "hour", "hours" -> value * 3_600_000L;
            case "day", "days" -> value * 86_400_000L;
            case "week", "weeks" -> value * 604_800_000L;
            default -> throw new IllegalArgumentException("Unknown rate unit: " + unit);
        };
    }

    /**
     * Returns the next fire instant at or after {@code from} for a cron expression
     * evaluated in {@code timezone} (default UTC).
     */
    public static Instant nextCronFire(String expression, Instant from, String timezone) {
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
        ExecutionTime exec = ExecutionTime.forCron(cron);

        ZoneId zone = resolveZone(timezone);
        ZonedDateTime zdt = from.atZone(zone);
        return exec.nextExecution(zdt)
                .map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalStateException(
                        "No next fire time for cron expression: " + expression));
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        return ZoneId.of(timezone);
    }
}
