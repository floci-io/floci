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
        return validKey(key) ? null : SmithyValidationErrors.Violations.WITHOUT_VALUES.mapKeys("tags", key,
                SmithyValidationErrors.constraintSet(1, KEY_MAX, TAG_PATTERN.pattern(), null));
    }

    public static String valueViolation(String value) {
        return validValue(value) ? null : SmithyValidationErrors.Violations.WITHOUT_VALUES.mapValues("tags", value,
                SmithyValidationErrors.constraintSet(0, VALUE_MAX, TAG_PATTERN.pattern(), null));
    }
}
