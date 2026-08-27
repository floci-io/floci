package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared SES resource-tag validation. Lives outside the facade so the extracted domain services can
 * validate tags without depending back on {@link SesService}.
 *
 * <p>Rules and messages are probe-confirmed against real AWS SES v2 (2026-08-23): at most 50 tags,
 * unique keys, key 1–128 / value 0–256 characters, both key and value restricted to letters, numbers,
 * spaces, and {@code _ . : / = + - @}, and the {@code aws:} key prefix reserved for system tags. The
 * evaluation order mirrors the observed AWS precedence: count, then character set (value before key),
 * then the reserved prefix, then the aggregated Smithy length errors, then empty keys, then duplicate
 * keys. All violations are {@code BadRequestException} / 400.
 */
final class SesTags {

    private static final int MAX_TAGS = 50;
    private static final int KEY_MAX = 128;
    private static final int VALUE_MAX = 256;
    // Letters, numbers, any Unicode whitespace/separator (\p{Z}, e.g. U+00A0 — probe-confirmed
    // accepted, not just the ASCII space), and _ . : / = + - @ (AWS's allowed tag character set).
    private static final Pattern TAG_CHARS = Pattern.compile("[\\p{L}\\p{N}\\p{Z}_.:/=+@-]*");
    private static final String CHARSET_MESSAGE =
            "Tags can only contain letters, numbers, spaces, and the following special characters: "
                    + "_ . : / = + - @";
    private static final String MAX_TAGS_MESSAGE =
            "Maximum of " + MAX_TAGS + " user tags are allowed per resource, consider reducing the "
                    + "number of tags in the request";
    // TagResource merges the incoming tags with the resource's existing tags before enforcing the
    // 50-tag ceiling, and uses a slightly different message than the create path (probe-confirmed).
    private static final String MAX_TAGS_MERGED_MESSAGE = MAX_TAGS_MESSAGE
            + " or delete existing tags and retry";
    private static final String SYSTEM_TAG_MESSAGE =
            "Caller is an end user and not allowed to mutate system tags";

    private SesTags() {}

    /**
     * Validate a tag collection the way AWS does: at most 50 tags with unique keys, each key/value
     * within its length bound and limited to the allowed character set, with the {@code aws:} key
     * prefix reserved. A {@code null}/empty list is a no-op.
     */
    static void validate(List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("BadRequestException", MAX_TAGS_MESSAGE, 400);
        }
        // Character set is checked before length, and value before key within a tag (probe-confirmed).
        for (Tag tag : tags) {
            if (tag == null || !charsetOk(tag.value()) || !charsetOk(tag.key())) {
                throw new AwsException("BadRequestException", CHARSET_MESSAGE, 400);
            }
        }
        // The aws: key prefix (case-insensitive) is reserved for system tags; only the key is checked
        // — a value starting with aws: is allowed. Checked after the character set (probe-confirmed).
        for (Tag tag : tags) {
            if (tag.key() != null && tag.key().regionMatches(true, 0, "aws:", 0, 4)) {
                throw new AwsException("BadRequestException", SYSTEM_TAG_MESSAGE, 400);
            }
        }
        // Length violations are reported together in the AWS Smithy form, value before key, with a
        // 1-based tag index. Smithy length constraints count Unicode code points, not UTF-16 code
        // units, so a supplementary-plane character counts as one.
        List<String> lengthErrors = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (tag.value() != null && codePointLength(tag.value()) > VALUE_MAX) {
                lengthErrors.add(lengthConstraint(i + 1, "value", VALUE_MAX));
            }
            if (tag.key() != null && codePointLength(tag.key()) > KEY_MAX) {
                lengthErrors.add(lengthConstraint(i + 1, "key", KEY_MAX));
            }
        }
        if (!lengthErrors.isEmpty()) {
            throw new AwsException("BadRequestException",
                    lengthErrors.size() + " validation error" + (lengthErrors.size() > 1 ? "s" : "")
                            + " detected: " + String.join("; ", lengthErrors), 400);
        }
        // An empty key is rejected with an empty body, matching AWS.
        for (Tag tag : tags) {
            if (tag.key() == null || tag.key().isEmpty()) {
                throw new AwsException("BadRequestException", null, 400);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Tag tag : tags) {
            if (!seen.add(tag.key())) {
                throw new AwsException("BadRequestException",
                        "Cannot provide multiple tags with the same key", 400);
            }
        }
    }

    /**
     * Enforce the 50-tag ceiling on the merged (existing + incoming) tag set that {@code TagResource}
     * persists. AWS applies the limit to the merged result — updating an existing key is allowed, but
     * a merge that would exceed 50 unique keys is rejected with the merge-specific message.
     */
    static void validateMergedCount(int mergedCount) {
        if (mergedCount > MAX_TAGS) {
            throw new AwsException("BadRequestException", MAX_TAGS_MERGED_MESSAGE, 400);
        }
    }

    /**
     * {@code TagResource} merge semantics: incoming keys overwrite existing values, insertion order
     * is preserved, and the 50-tag ceiling applies to the merged result.
     */
    static List<Tag> merge(List<Tag> existing, List<Tag> incoming) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Tag t : existing) {
            merged.put(t.key(), t.value());
        }
        for (Tag t : incoming) {
            merged.put(t.key(), t.value());
        }
        validateMergedCount(merged.size());
        List<Tag> out = new ArrayList<>();
        merged.forEach((k, v) -> out.add(new Tag(k, v)));
        return out;
    }

    // A null value counts as a character-set violation (AWS requires a string value; empty is allowed).
    private static boolean charsetOk(String s) {
        return s != null && TAG_CHARS.matcher(s).matches();
    }

    private static int codePointLength(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String lengthConstraint(int index, String member, int max) {
        return "Value at 'tags." + index + ".member." + member + "' failed to satisfy constraint: "
                + "Member must have length less than or equal to " + max;
    }
}
