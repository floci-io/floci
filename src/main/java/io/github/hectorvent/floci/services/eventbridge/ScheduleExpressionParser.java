package io.github.hectorvent.floci.services.eventbridge;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScheduleExpressionParser {

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "^rate\\(\\s*(\\d+)\\s+(minutes?|hours?|days?|weeks?)\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^cron\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final CronParser CRON_PARSER;

    static {
        CronDefinition definition = CronDefinitionBuilder.defineCron()
                .withSeconds().and()
                .withMinutes().and()
                .withHours().and()
                .withDayOfMonth().supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withMonth().and()
                .withDayOfWeek().supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withYear().optional().and()
                .instance();
        CRON_PARSER = new CronParser(definition);
    }

    private ScheduleExpressionParser() {}

    public static boolean isRateExpression(String expression) {
        return expression != null && RATE_PATTERN.matcher(expression.trim()).matches();
    }

    public static boolean isCronExpression(String expression) {
        return expression != null && CRON_PATTERN.matcher(expression.trim()).matches();
    }

    public static long parseRateToMillis(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule expression cannot be null or blank");
        }

        Matcher rateMatcher = RATE_PATTERN.matcher(expression.trim());
        if (!rateMatcher.matches()) {
            throw new IllegalArgumentException("Not a valid rate expression: " + expression);
        }

        int value = Integer.parseInt(rateMatcher.group(1));
        if (value < 1) {
            throw new IllegalArgumentException("Rate value must be >= 1, got: " + value);
        }
        String unit = rateMatcher.group(2).toLowerCase();

        return switch (unit) {
            case "minute", "minutes" -> value * 60 * 1000L;
            case "hour", "hours" -> value * 60 * 60 * 1000L;
            case "day", "days" -> value * 24 * 60 * 60 * 1000L;
            case "week", "weeks" -> value * 7 * 24 * 60 * 60 * 1000L;
            default -> throw new IllegalArgumentException("Unknown rate unit: " + unit);
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
        long millis = java.time.temporal.ChronoUnit.MILLIS.between(from, next);
        return Math.max(millis, 1000);
    }

    private static String normalizeCronExpression(String cronExpression) {
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                    "AWS EventBridge cron expressions require 6 fields (minute hour day-of-month month day-of-week year), got " + fields.length + ": " + cronExpression);
        }
        return "0 " + cronExpression;
    }
}
