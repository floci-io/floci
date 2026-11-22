package io.floci.aws.core.common;

import java.util.regex.Pattern;

/** Key and value constraints shared by Bedrock and Bedrock AgentCore tags. */
public final class BedrockTagKeyValueRules {

    public static final int KEY_MAX = 128;
    public static final int VALUE_MAX = 256;
    public static final String TAG_PATTERN = "[a-zA-Z0-9\\s._:/=+@-]*";
    private static final Pattern TAG_CHARACTERS = Pattern.compile(TAG_PATTERN);

    private BedrockTagKeyValueRules() {
    }

    public static boolean validKey(String key) {
        return !key.isEmpty() && key.length() <= KEY_MAX && TAG_CHARACTERS.matcher(key).matches();
    }

    public static boolean validValue(String value) {
        return value.length() <= VALUE_MAX && TAG_CHARACTERS.matcher(value).matches();
    }
}
