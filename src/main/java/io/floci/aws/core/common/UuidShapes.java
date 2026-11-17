package io.floci.aws.core.common;

import java.util.regex.Pattern;

/**
 * The canonical 8-4-4-4-12 UUID text form, tested as a shape only: no version or variant is read.
 * Which case a service accepts is part of its wire behaviour, so the two checks are not interchangeable.
 */
public final class UuidShapes {

    private static final Pattern EITHER_CASE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LOWER_CASE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private UuidShapes() {
    }

    /** Whether the whole value is 8-4-4-4-12 hex digits of either case. A null value is not. */
    public static boolean matchesEitherCase(String value) {
        return value != null && EITHER_CASE.matcher(value).matches();
    }

    /** Whether the whole value is 8-4-4-4-12 lower-case hex digits. A null value is not. */
    public static boolean matchesLowerCase(String value) {
        return value != null && LOWER_CASE.matcher(value).matches();
    }
}
