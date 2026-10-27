package io.github.hectorvent.floci.core.common;

import java.util.List;

/**
 * The message AWS's Smithy validation layer answers when a request breaks its model's constraints:
 * "1 validation error detected: a" or "N validation errors detected: a; b", each violation reading
 * "Value at 'path' failed to satisfy constraint: ...". The wording is Smithy's, shared by every
 * service built on it; only the error code differs by protocol.
 */
public final class SmithyValidationErrors {

    private SmithyValidationErrors() {}

    /**
     * The message for one or more violations. An empty list is the caller's bug, so it fails rather
     * than answering "0 validation errors"; check {@code isEmpty()} before throwing.
     */
    public static String message(List<String> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("no violations to report");
        }
        String count = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return count + String.join("; ", violations);
    }

    /** The violation for a required member the request leaves out. */
    public static String notNull(String path) {
        return "Value null at '" + path + "' failed to satisfy constraint: Member must not be null";
    }

    /** The same violation in the form some operations answer, without {@code null} after "Value". */
    public static String notNullWithoutValue(String path) {
        return "Value at '" + path + "' failed to satisfy constraint: Member must not be null";
    }

    /** The 400 error carrying {@link #message} under the protocol's validation error code. */
    public static AwsException exception(String errorCode, List<String> violations) {
        return new AwsException(errorCode, message(violations), 400);
    }

    public static AwsException exception(String errorCode, String violation) {
        return exception(errorCode, List.of(violation));
    }

    /** The code most JSON services use, on both the awsJson and the restJson protocols. */
    public static AwsException validationException(List<String> violations) {
        return exception("ValidationException", violations);
    }

    public static AwsException validationException(String violation) {
        return validationException(List.of(violation));
    }

    /** The awsQuery protocol's code. */
    public static AwsException validationError(List<String> violations) {
        return exception("ValidationError", violations);
    }

    public static AwsException validationError(String violation) {
        return validationError(List.of(violation));
    }

    /** The code of REST JSON services that model their validation failure as BadRequestException. */
    public static AwsException badRequest(List<String> violations) {
        return exception("BadRequestException", violations);
    }

    public static AwsException badRequest(String violation) {
        return badRequest(List.of(violation));
    }

    /** The violation for a number outside {@code min} to {@code max}, or {@code null} for one inside. */
    public static String outOfRange(String path, long value, long min, long max) {
        if (value < min) {
            return valueTooSmall(path, Long.toString(value), min);
        }
        if (value > max) {
            return valueTooLarge(path, Long.toString(value), max);
        }
        return null;
    }

    /**
     * The violation for a number below {@code min}, with the value already written: a number too
     * large for a {@code long} is still a number, and the bound it breaks is one that fits in one.
     */
    public static String valueTooSmall(String path, String value, long min) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must have value greater than or equal to " + min;
    }

    /** The violation for a number above {@code max}, with the value already written. */
    public static String valueTooLarge(String path, String value, long max) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must have value less than or equal to " + max;
    }

    /** The violation for a string outside its length bounds, or {@code null} for one inside. */
    public static String lengthOutOfRange(String path, String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < min) {
            return lengthTooShort(path, value, min);
        }
        if (length > max) {
            return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                    + "Member must have length less than or equal to " + max;
        }
        return null;
    }

    /**
     * The violation for a list holding a member count outside its bounds, or {@code null} for one
     * inside. The offending value is left out, which is the form this package already answers for a
     * tag list and for an audience: quoting it would echo every member of the list back in the
     * message.
     */
    public static String sizeOutOfRange(String path, int size, int min, int max) {
        if (size < min) {
            return "Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to " + min;
        }
        if (size > max) {
            return "Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must have length less than or equal to " + max;
        }
        return null;
    }

    /** Also used by services that reject whitespace-only input as an empty member. */
    public static String lengthTooShort(String path, String value, int min) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must have length greater than or equal to " + min;
    }

    /** The violation for a string that does not match the pattern its shape declares. */
    public static String pattern(String path, String value, String pattern) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: " + pattern;
    }

    /** The violation for a value outside its enum, listing the allowed values in the model's order. */
    public static String notInEnum(String value, String path, List<String> allowed) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must satisfy enum value set: " + allowed;
    }

    /**
     * The violation for a list holding a value outside its members' enum. The list is checked as a
     * whole, so the whole list is the offending value and the enum constraint is nested.
     */
    public static String notAllInEnum(List<String> values, String path, List<String> allowed) {
        return "Value '" + values + "' at '" + path + "' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must satisfy enum value set: " + allowed + "]";
    }
}
