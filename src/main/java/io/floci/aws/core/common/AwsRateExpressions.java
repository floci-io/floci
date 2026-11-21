package io.floci.aws.core.common;

import java.util.Locale;
import java.util.Set;

/** Rate constraints shared by EventBridge rules and EventBridge Scheduler API requests. */
public final class AwsRateExpressions {

    private static final Set<String> UNITS = Set.of("minute", "minutes", "hour", "hours", "day", "days");

    private AwsRateExpressions() {
    }

    /** Validates a parsed rate without restricting the parsers used for previously stored schedules. */
    public static void validate(long value, String unit) {
        requirePositiveValue(value);
        String normalizedUnit = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        if (!UNITS.contains(normalizedUnit)) {
            throw new IllegalArgumentException("Rate unit must be minute(s), hour(s), or day(s)");
        }
        boolean plural = normalizedUnit.endsWith("s");
        if ((value == 1) == plural) {
            throw new IllegalArgumentException("Rate unit must be singular for 1 and plural for values greater than 1");
        }
    }

    /** Positive values are also required when executing previously stored expressions. */
    public static void requirePositiveValue(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("Rate value must be >= 1, got: " + value);
        }
    }
}
