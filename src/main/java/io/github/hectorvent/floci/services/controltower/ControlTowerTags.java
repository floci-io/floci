package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tag rule Control Tower applies wherever a landing zone or an enabled control takes tags: at most 200
 * entries, each a non-blank key of up to 128 characters and a string value of up to 256.
 */
final class ControlTowerTags {

    private static final int MAX_TAGS = 200;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 256;

    private ControlTowerTags() {
    }

    /** The optional {@code tags} member of a create request, validated; empty when absent. */
    static Map<String, String> fromRequest(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return Map.of();
        }
        if (!tags.isObject()) {
            throw invalid();
        }
        Map<String, String> values = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalid();
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        validate(values);
        return values;
    }

    static void validate(Map<String, String> tags) {
        if (tags.size() > MAX_TAGS) {
            throw invalid();
        }
        tags.forEach((key, value) -> {
            if (key.isBlank() || key.length() > MAX_KEY_LENGTH || value == null || value.length() > MAX_VALUE_LENGTH) {
                throw invalid();
            }
        });
    }

    private static AwsException invalid() {
        return new AwsException("ValidationException",
                "tags must hold at most 200 entries, each a non-blank key of up to 128 characters"
                        + " and a string value of up to 256.", 400);
    }
}
