package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code iam:} condition keys an IAM request carries in its own members, and those read from the
 * role or user it names, generated from the AWS Service Reference and pinned botocore by
 * {@code tools/aws/regen_iam_condition_keys.py}.
 */
final class IamConditionKeys {

    private record Table(Map<String, Map<String, String>> members, Map<String, Map<String, String>> targets) {}

    private static final Table TABLE = VendoredResources.read(
            "aws/iam-condition-keys.json", "IAM condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                return new Table(section(document.path("read"), "member"),
                        section(document.path("fromTarget"), "target"));
            });

    private IamConditionKeys() {}

    private static Map<String, Map<String, String>> section(JsonNode entries, String field) {
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalStateException("Malformed IAM condition key table");
        }
        Map<String, Map<String, String>> byAction = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            if (!entry.path("action").isTextual() || !entry.path("key").isTextual()
                    || !entry.path(field).isTextual()) {
                throw new IllegalStateException("Malformed IAM condition key entry: " + entry);
            }
            byAction.computeIfAbsent(entry.get("action").asText(), action -> new LinkedHashMap<>())
                    .put(entry.get("key").asText(), entry.get(field).asText());
        }
        byAction.replaceAll((action, keys) -> Map.copyOf(keys));
        return Map.copyOf(byAction);
    }

    /** Each condition key a request for {@code action} carries, to the member that holds it; empty when none. */
    static Map<String, String> members(String action) {
        return TABLE.members().getOrDefault(action, Map.of());
    }

    /**
     * Each condition key a request for {@code action} takes from the stored role or user it names, to the
     * member that names it ({@code RoleName} or {@code UserName}); empty when none.
     */
    static Map<String, String> targets(String action) {
        return TABLE.targets().getOrDefault(action, Map.of());
    }
}
