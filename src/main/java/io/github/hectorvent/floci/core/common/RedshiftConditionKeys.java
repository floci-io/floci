package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code redshift:} condition keys a Redshift request carries in its own members, generated from the AWS
 * Service Reference and pinned botocore by {@code tools/aws/regen_redshift_condition_keys.py}.
 */
final class RedshiftConditionKeys {

    private static final Map<String, Map<String, String>> MEMBERS = VendoredResources.read(
            "aws/redshift-condition-keys.json", "Redshift condition keys", input -> {
                JsonNode members = new ObjectMapper().readTree(input).path("members");
                if (!members.isObject() || members.isEmpty()) {
                    throw new IllegalStateException("Malformed Redshift condition key table");
                }
                Map<String, Map<String, String>> byAction = new LinkedHashMap<>();
                members.properties().forEach(action -> {
                    Map<String, String> keys = new LinkedHashMap<>();
                    action.getValue().properties().forEach(key -> {
                        if (!key.getValue().isTextual()) {
                            throw new IllegalStateException("Malformed Redshift condition key entry: " + action);
                        }
                        keys.put(key.getKey(), key.getValue().asText());
                    });
                    byAction.put(action.getKey(), Map.copyOf(keys));
                });
                return Map.copyOf(byAction);
            });

    private RedshiftConditionKeys() {}

    /** Each condition key a request for {@code action} carries, to the member that holds it; empty when none. */
    static Map<String, String> members(String action) {
        return MEMBERS.getOrDefault(action, Map.of());
    }
}
