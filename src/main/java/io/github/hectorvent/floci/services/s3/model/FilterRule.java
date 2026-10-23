package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single S3 notification filter rule (prefix or suffix match on the object key).
 *
 * @param name  "prefix" or "suffix"
 * @param value the value to match against
 */
@RegisterForReflection
public record FilterRule(String name, String value) {

    /**
     * The percent-encoded forms of {@code ! ' ( ) *}, the special characters S3 lists as safe in a
     * key, and of {@code ~}, which a filter may carry raw or encoded, in either hex case.
     */
    private static final Pattern ENCODED_SAFE_CHARACTER = Pattern.compile("%(21|27|28|29|2[Aa]|7[Ee])");

    /**
     * Matches the rule against {@code key} as the event record encodes it. A safe character and its
     * percent-encoded form count as the same character on both sides.
     */
    public boolean matches(String key) {
        if (key == null || name == null || value == null) return false;
        String normalizedKey = normalized(key);
        String normalizedValue = normalized(value);
        return switch (name.toLowerCase()) {
            case "prefix" -> normalizedKey.startsWith(normalizedValue);
            case "suffix" -> normalizedKey.endsWith(normalizedValue);
            default -> false;
        };
    }

    /** {@code text} in the form a filter compares it in, so two values match the same keys when equal. */
    public static String normalized(String text) {
        Matcher matcher = ENCODED_SAFE_CHARACTER.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(match.group(1), 16))));
    }
}
