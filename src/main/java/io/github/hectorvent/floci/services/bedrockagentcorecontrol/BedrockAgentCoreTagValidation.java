package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

/**
 * AgentCore's tag key/value validation rules, shared by the service classes under this package
 * that accept a {@code tags} field on create/update requests. {@code BedrockAgentCoreCredentialProviderService}
 * and {@code BedrockAgentCoreToolsService} each carried their own copy of this, found duplicated
 * by CPD.
 */
final class BedrockAgentCoreTagValidation {

    private BedrockAgentCoreTagValidation() {
    }

    static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw new AwsException("ValidationException", "tags must be an object with at most 50 entries", 400);
        }
        tags.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode rawValue = entry.getValue();
            if (key.length() < 1 || key.length() > 128 || !key.matches("[a-zA-Z0-9\\s._:/=+@-]*")) {
                throw new AwsException("ValidationException", "tag key does not satisfy AgentCore constraints", 400);
            }
            if (!rawValue.isTextual()) {
                throw new AwsException("ValidationException", "tag value must be a string", 400);
            }
            String value = rawValue.asText();
            if (value.length() > 256 || !value.matches("[a-zA-Z0-9\\s._:/=+@-]*")) {
                throw new AwsException("ValidationException", "tag value does not satisfy AgentCore constraints", 400);
            }
        });
    }
}
