package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code iam:} condition keys an IAM request carries in its own members, those read from the
 * role or user it names, and those read from the record of the resource it names, generated from the
 * AWS Service Reference and pinned botocore by {@code tools/aws/regen_iam_condition_keys.py}.
 */
final class IamConditionKeys {

    record RequestTags(String member, String keyMember, String valueMember) {}

    /**
     * A key the reference publishes on a resource type: the type, the member of its record the value is read
     * from, and the pattern the type's ARN format makes, which says whether an ARN is one of that type.
     */
    record Resource(String type, String member, Pattern arn) {}

    private record Table(Map<String, Map<String, String>> members, Map<String, Map<String, String>> targets,
                         Map<String, Map<String, Resource>> resources,
                         Map<String, Map<String, RequestTags>> requestTags,
                         Map<String, Set<String>> unreadTagPrefixes) {}

    private static final String TAG_KEY = "${TagKey}";

    private static final Table TABLE = VendoredResources.read(
            "aws/iam-condition-keys.json", "IAM condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                return new Table(section(document.path("read"), entry -> text(entry, "member")),
                        section(document.path("fromTarget"), entry -> text(entry, "target")),
                        section(document.path("fromResource"), entry -> new Resource(text(entry, "type"),
                                text(entry, "member"),
                                RequestResourceMembers.pattern(text(entry, "arn")))),
                        section(document.path("requestTags"), entry -> new RequestTags(text(entry, "member"),
                                entry.has("keyMember") ? text(entry, "keyMember") : null,
                                entry.has("keyMember") ? text(entry, "valueMember") : null)),
                        unreadTagPrefixes(document.path("notRead")));
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

    /**
     * For each action, the prefixes of the tag keys the table does not read for it ({@code aws:ResourceTag/} for a
     * {@code notRead} entry on {@code aws:ResourceTag/${TagKey}}).
     */
    private static Map<String, Set<String>> unreadTagPrefixes(JsonNode entries) {
        Map<String, Set<String>> byAction = new LinkedHashMap<>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String key = text(entry, "key");
                if (key.endsWith(TAG_KEY)) {
                    byAction.computeIfAbsent(text(entry, "action"), action -> new HashSet<>())
                            .add(key.substring(0, key.length() - TAG_KEY.length()));
                }
            }
        }
        byAction.replaceAll((action, prefixes) -> Set.copyOf(prefixes));
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

    /**
     * Each condition key a request for {@code action} takes from the resource it names, to the type of that
     * resource and the member of its record holding the value; empty when none.
     */
    static Map<String, Resource> resources(String action) {
        return TABLE.resources().getOrDefault(action, Map.of());
    }

    static Map<String, RequestTags> requestTags(String action) {
        return TABLE.requestTags().getOrDefault(action, Map.of());
    }

    /**
     * The prefixes of the tag keys ({@code aws:ResourceTag/}, {@code iam:ResourceTag/}) the table does not read for
     * {@code action}: {@code iam:PassRole}'s resource-tag keys, AWS warning that ResourceTag conditions with PassRole
     * "do not have reliable results". Empty when it reads them all.
     */
    static Set<String> unreadTagPrefixes(String action) {
        return action == null ? Set.of() : TABLE.unreadTagPrefixes().getOrDefault(action, Set.of());
    }
}
