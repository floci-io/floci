package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;

import java.util.regex.Pattern;

/** Validates the Bedrock TagList shape. */
public final class BedrockTagValidator {

    private static final int TAG_LIST_MAX = 200;
    private static final int TAG_KEY_MIN = 1;
    private static final int TAG_KEY_MAX = 128;
    private static final int TAG_VALUE_MAX = 256;
    private static final Pattern TAG_PATTERN = Pattern.compile("[a-zA-Z0-9\\s._:/=+@-]*");

    private BedrockTagValidator() {
    }

    public static void validateRequired(JsonNode tags, String keyProperty, String valueProperty,
                                        String errorCode, String propertyName) {
        if (tags == null || tags.isNull()) {
            throw invalid(errorCode, propertyName + " is required.");
        }
        validate(tags, keyProperty, valueProperty, errorCode, propertyName);
    }

    public static void validate(JsonNode tags, String keyProperty, String valueProperty,
                                String errorCode, String propertyName) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isArray()) {
            throw invalid(errorCode, propertyName + " must be an array.");
        }
        if (tags.size() > TAG_LIST_MAX) {
            throw invalid(errorCode, propertyName + " must have at most " + TAG_LIST_MAX + " items.");
        }
        for (JsonNode tag : tags) {
            JsonNode key = tag.path(keyProperty);
            JsonNode value = tag.path(valueProperty);
            if (!tag.isObject() || !key.isTextual() || !value.isTextual()) {
                throw invalid(errorCode, propertyName + " items require string "
                        + keyProperty + " and " + valueProperty + ".");
            }
            String keyText = key.textValue();
            String valueText = value.textValue();
            if (keyText.length() < TAG_KEY_MIN || keyText.length() > TAG_KEY_MAX
                    || !TAG_PATTERN.matcher(keyText).matches()) {
                throw invalid(errorCode, propertyName + " item " + keyProperty + " is invalid.");
            }
            if (valueText.length() > TAG_VALUE_MAX || !TAG_PATTERN.matcher(valueText).matches()) {
                throw invalid(errorCode, propertyName + " item " + valueProperty + " is invalid.");
            }
        }
    }

    private static AwsException invalid(String errorCode, String message) {
        return new AwsException(errorCode, message, 400);
    }
}
