package io.floci.aws.core.common;

import java.util.regex.Pattern;

/** Key and value constraints shared by Bedrock and Bedrock AgentCore tags. */
public final class BedrockTagKeyValueRules {

    private static final int KEY_MAX = 128;
    private static final int VALUE_MAX = 256;
    private static final Pattern TAG_PATTERN = Pattern.compile("[a-zA-Z0-9\\s._:/=+@-]*");

    private BedrockTagKeyValueRules() {
    }

    public static boolean validKey(String key) {
        return !key.isEmpty() && key.length() <= KEY_MAX && TAG_PATTERN.matcher(key).matches();
    }

    public static boolean validValue(String value) {
        return value.length() <= VALUE_MAX && TAG_PATTERN.matcher(value).matches();
    }

    public static String keyViolation(String key) {
        return stringViolation("tags.key", key, 1, KEY_MAX);
    }

    public static String valueViolation(String value) {
        return stringViolation("tags.value", value, 0, VALUE_MAX);
    }

    private static String stringViolation(String path, String value, int minLength, int maxLength) {
        if (value == null) {
            return SmithyValidationErrors.notNull(path);
        }
        String lengthViolation = SmithyValidationErrors.lengthOutOfRange(path, value, minLength, maxLength);
        if (lengthViolation != null) {
            return lengthViolation;
        }
        return TAG_PATTERN.matcher(value).matches() ? null
                : SmithyValidationErrors.pattern(path, value, TAG_PATTERN.pattern());
    }
}
