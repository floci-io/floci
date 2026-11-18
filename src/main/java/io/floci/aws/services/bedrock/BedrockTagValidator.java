package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.BedrockTagKeyValueRules;

/** Validates the Bedrock TagList shape. */
public final class BedrockTagValidator {

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
        for (JsonNode tag : tags) {
            JsonNode key = tag.path(keyProperty);
            JsonNode value = tag.path(valueProperty);
            if (!tag.isObject() || !key.isTextual() || !value.isTextual()) {
                throw invalid(errorCode, propertyName + " items require string "
                        + keyProperty + " and " + valueProperty + ".");
            }
            String keyText = key.textValue();
            String valueText = value.textValue();
            if (!BedrockTagKeyValueRules.validKey(keyText)) {
                throw invalid(errorCode, propertyName + " item " + keyProperty + " is invalid.");
            }
            if (!BedrockTagKeyValueRules.validValue(valueText)) {
                throw invalid(errorCode, propertyName + " item " + valueProperty + " is invalid.");
            }
        }
    }

    private static AwsException invalid(String errorCode, String message) {
        return new AwsException(errorCode, message, 400);
    }
}
