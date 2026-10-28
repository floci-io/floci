package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code iam:} condition keys an IAM request carries in its own members, generated from the
 * AWS Service Reference and pinned botocore by {@code tools/aws/regen_iam_condition_keys.py}.
 */
final class IamConditionKeys {

    private static final Map<String, Map<String, String>> MEMBERS = VendoredResources.read(
            "aws/iam-condition-keys.json", "IAM condition keys", input -> {
                JsonNode read = new ObjectMapper().readTree(input).path("read");
                if (!read.isArray() || read.isEmpty()) {
                    throw new IllegalStateException("Malformed IAM condition key table");
                }
                Map<String, Map<String, String>> members = new LinkedHashMap<>();
                for (JsonNode entry : read) {
                    if (!entry.path("action").isTextual() || !entry.path("key").isTextual()
                            || !entry.path("member").isTextual()) {
                        throw new IllegalStateException("Malformed IAM condition key entry: " + entry);
                    }
                    members.computeIfAbsent(entry.get("action").asText(), action -> new LinkedHashMap<>())
                            .put(entry.get("key").asText(), entry.get("member").asText());
                }
                members.replaceAll((action, keys) -> Map.copyOf(keys));
                return Map.copyOf(members);
            });

    private IamConditionKeys() {}

    /** Each condition key a request for {@code action} carries, to the member that holds it; empty when none. */
    static Map<String, String> members(String action) {
        return MEMBERS.getOrDefault(action, Map.of());
    }
}
