package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.BedrockTagKeyValueRules;
import io.floci.aws.core.common.SmithyValidationErrors;

import java.util.Map;

/**
 * AgentCore's tag key/value validation rules, shared by the service classes under this package
 * that accept a {@code tags} field on create/update requests. {@code BedrockAgentCoreCredentialProviderService}
 * and {@code BedrockAgentCoreToolsService} each carried their own copy of this, found duplicated
 * by CPD.
 */
final class BedrockAgentCoreTagValidation {

    private static final int MAX_TAGS = 50;

    private BedrockAgentCoreTagValidation() {
    }

    static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject()) {
            throw new AwsException("ValidationException",
                    "tags must be an object with at most " + MAX_TAGS + " entries", 400);
        }
        validateTagCount(tags.size());
        tags.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode rawValue = entry.getValue();
            validateKey(key);
            if (!rawValue.isTextual()) {
                throw new AwsException("ValidationException", "tag value must be a string", 400);
            }
            validateValue(rawValue.asText());
        });
    }

    static void validateTagMap(Map<String, String> tags) {
        if (tags == null) {
            throw new AwsException("ValidationException",
                    "tags must be an object with at most " + MAX_TAGS + " entries", 400);
        }
        validateTagCount(tags.size());
        tags.forEach((key, value) -> {
            validateKey(key);
            validateValue(value);
        });
    }

    private static void validateKey(String key) {
        String violation = BedrockTagKeyValueRules.keyViolation(key);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
    }

    private static void validateValue(String value) {
        if (value == null) {
            throw new AwsException("ValidationException", "tag value must be a string", 400);
        }
        String violation = BedrockTagKeyValueRules.valueViolation(value);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
    }

    private static void validateTagCount(int size) {
        String violation = SmithyValidationErrors.sizeOutOfRange("tags", size, 0, MAX_TAGS);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
    }

}
