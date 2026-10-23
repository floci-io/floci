package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;
import java.util.Set;
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

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%([0-9A-Fa-f]{2})");

    /**
     * The percent-encoded forms of {@code ! ' ( ) *}, the special characters S3 lists as safe in a
     * key, and of {@code ~}, which a filter may carry raw or encoded.
     */
    private static final Set<String> SAFE_CHARACTER_ESCAPES = Set.of("21", "27", "28", "29", "2A", "7E");

    /**
     * Matches the rule against {@code key} as the event record encodes it. A safe character and its
     * percent-encoded form count as the same character on both sides, and a percent-escape matches
     * in either hex case.
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
        Matcher matcher = PERCENT_ESCAPE.matcher(text);
        return matcher.replaceAll(match -> {
            String hex = match.group(1).toUpperCase(Locale.ROOT);
            String replacement = SAFE_CHARACTER_ESCAPES.contains(hex)
                    ? String.valueOf((char) Integer.parseInt(hex, 16))
                    : "%" + hex;
            return Matcher.quoteReplacement(replacement);
        });
    }
}
