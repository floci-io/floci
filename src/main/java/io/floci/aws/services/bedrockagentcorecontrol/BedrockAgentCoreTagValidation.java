package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.BedrockTagKeyValueRules;

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
        if (!tags.isObject() || tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "tags must be an object with at most " + MAX_TAGS + " entries", 400);
        }
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
        if (tags == null || tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "tags must be an object with at most " + MAX_TAGS + " entries", 400);
        }
        tags.forEach((key, value) -> {
            validateKey(key);
            validateValue(value);
        });
    }

    private static void validateKey(String key) {
        if (key == null || !BedrockTagKeyValueRules.validKey(key)) {
            throw new AwsException("ValidationException", "tag key does not satisfy AgentCore constraints", 400);
        }
    }

    private static void validateValue(String value) {
        if (value == null) {
            throw new AwsException("ValidationException", "tag value must be a string", 400);
        }
        if (!BedrockTagKeyValueRules.validValue(value)) {
            throw new AwsException("ValidationException", "tag value does not satisfy AgentCore constraints", 400);
        }
    }
}
