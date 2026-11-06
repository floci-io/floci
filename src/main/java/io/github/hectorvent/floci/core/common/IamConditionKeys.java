package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code iam:} condition keys an IAM request carries in its own members, and those read from the
 * role or user it names, generated from the AWS Service Reference and pinned botocore by
 * {@code tools/aws/regen_iam_condition_keys.py}.
 */
final class IamConditionKeys {

    record RequestTags(String member, String keyMember, String valueMember) {}

    private record Table(Map<String, Map<String, String>> members, Map<String, Map<String, String>> targets,
                         Map<String, Map<String, RequestTags>> requestTags) {}

    private static final Table TABLE = VendoredResources.read(
            "aws/iam-condition-keys.json", "IAM condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                return new Table(section(document.path("read"), entry -> text(entry, "member")),
                        section(document.path("fromTarget"), entry -> text(entry, "target")),
                        section(document.path("requestTags"), entry -> new RequestTags(text(entry, "member"),
                                entry.has("keyMember") ? text(entry, "keyMember") : null,
                                entry.has("keyMember") ? text(entry, "valueMember") : null)));
            });

    private IamConditionKeys() {}

    private static <T> Map<String, Map<String, T>> section(JsonNode entries, Function<JsonNode, T> value) {
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalStateException("Malformed IAM condition key table");
        }
        Map<String, Map<String, T>> byAction = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            byAction.computeIfAbsent(text(entry, "action"), action -> new LinkedHashMap<>())
                    .put(text(entry, "key"), value.apply(entry));
        }
        byAction.replaceAll((action, keys) -> Map.copyOf(keys));
        return Map.copyOf(byAction);
    }

    private static String text(JsonNode entry, String field) {
        if (!entry.path(field).isTextual()) {
            throw new IllegalStateException("Malformed IAM condition key entry: " + entry);
        }
        return entry.get(field).asText();
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

    static Map<String, RequestTags> requestTags(String action) {
        return TABLE.requestTags().getOrDefault(action, Map.of());
    }
}
