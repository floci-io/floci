package io.floci.aws.services.eventbridge;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.floci.aws.core.common.AwsCronDefinitions;
import io.floci.aws.core.common.AwsRateExpressions;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScheduleExpressionParser {

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "^rate\\(\\s*(\\d+)\\s+(minutes?|hours?|days?|weeks?)\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^cron\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final CronParser CRON_PARSER = AwsCronDefinitions.newParser();

    private ScheduleExpressionParser() {}

    public static boolean isRateExpression(String expression) {
        return expression != null && RATE_PATTERN.matcher(expression.trim()).matches();
    }

    public static boolean isCronExpression(String expression) {
        return expression != null && CRON_PATTERN.matcher(expression.trim()).matches();
    }

    /** Validates API input without changing parsing of previously stored expressions. */
    public static void validate(String expression) {
        if (isCronExpression(expression)) {
            getNextFireTime(expression, ZonedDateTime.now(ZoneOffset.UTC));
            return;
        }
        Rate rate = parseRate(expression);
        AwsRateExpressions.validate(rate.value(), rate.unit());
    }

    public static long parseRateToMillis(String expression) {
        Rate rate = parseRate(expression);
        int value = rate.value();
        return switch (rate.unit()) {
            case "minute", "minutes" -> value * 60 * 1000L;
            case "hour", "hours" -> value * 60 * 60 * 1000L;
            case "day", "days" -> value * 24 * 60 * 60 * 1000L;
            case "week", "weeks" -> value * 7 * 24 * 60 * 60 * 1000L;
            default -> throw new IllegalArgumentException("Unknown rate unit: " + rate.unit());
        };
    }

    public static ZonedDateTime getNextFireTime(String expression, ZonedDateTime from) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule expression cannot be null or blank");
        }

        Matcher cronMatcher = CRON_PATTERN.matcher(expression.trim());
        if (!cronMatcher.matches()) {
            throw new IllegalArgumentException("Expected cron expression but got: " + expression);
        }

        String cronExpression = cronMatcher.group(1);
        String normalized = normalizeCronExpression(cronExpression);
        Cron cron = CRON_PARSER.parse(normalized);
        cron.validate();

        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        return executionTime.nextExecution(from).orElse(null);
    }

    public static long millisUntilNextFire(String expression, ZonedDateTime from) {
        ZonedDateTime next = getNextFireTime(expression, from);
        if (next == null) {
            throw new IllegalStateException("No next fire time found for cron expression: " + expression);
        }
        long millis = ChronoUnit.MILLIS.between(from, next);
        return Math.max(millis, 1000);
    }

    private static Rate parseRate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule expression cannot be null or blank");
        }
        Matcher matcher = RATE_PATTERN.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a valid rate expression: " + expression);
        }
        int value = Integer.parseInt(matcher.group(1));
        AwsRateExpressions.requirePositiveValue(value);
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return new Rate(value, unit);
    }

    private static String normalizeCronExpression(String cronExpression) {
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                    "AWS EventBridge cron expressions require 6 fields (minute hour day-of-month month day-of-week year), got " + fields.length + ": " + cronExpression);
        }
        return "0 " + cronExpression;
    }

    private record Rate(int value, String unit) {}
}
