package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.VendoredResources;
import io.github.hectorvent.floci.core.common.XmlParser;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The request tag condition keys of an S3 Control request, {@code aws:RequestTag/<key>} and
 * {@code aws:TagKeys}, read from the table {@code tools/aws/regen_s3control_tag_condition_keys.py}
 * generates: the actions the Service Reference gives either key, and the request members botocore's
 * {@code s3control} model carries them in. S3 Control's routes are checked as the action of their
 * operation's name, so an action names its operation's members.
 */
public final class S3ControlTagConditionKeys {

    private static final String REQUEST_TAG = "aws:RequestTag/${TagKey}";
    private static final String TAG_KEYS = "aws:TagKeys";
    private static final String REQUEST_TAG_PREFIX = "aws:RequestTag/";

    private static final JsonNode TABLE = VendoredResources.read("aws/s3control-tag-condition-keys.json",
            "S3 Control tag condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                if (!document.path("actions").isObject() || document.path("actions").isEmpty()
                        || !document.path("members").isObject() || document.path("members").isEmpty()) {
                    throw new IllegalStateException("Malformed S3 Control tag condition key table");
                }
                return document;
            });

    private S3ControlTagConditionKeys() {}

    /**
     * The request tag keys {@code action} takes, from the members of the request that carry them:
     * the tags in {@code body}, read as {@code S3ControlController} reads them, and the tag keys in
     * {@code query}. Null when the action takes neither key or the request carries no tags.
     */
    public static Map<String, List<String>> conditions(String action, Supplier<String> body,
                                                       MultivaluedMap<String, String> query) {
        JsonNode members = TABLE.path("members").path(action == null ? "" : action);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (takes(action, REQUEST_TAG)) {
            for (JsonNode member : members.path(REQUEST_TAG)) {
                bodyTags(member, body).forEach((key, value) -> conditions.put(REQUEST_TAG_PREFIX + key, List.of(value)));
            }
        }
        if (takes(action, TAG_KEYS)) {
            List<String> keys = new ArrayList<>();
            for (JsonNode member : members.path(TAG_KEYS)) {
                List<String> named = "querystring".equals(member.path("location").asText())
                        ? query.getOrDefault(member.path("member").asText(), List.of())
                        : List.copyOf(bodyTags(member, body).keySet());
                named.stream().filter(key -> !keys.contains(key)).forEach(keys::add);
            }
            if (!keys.isEmpty()) {
                conditions.put(TAG_KEYS, keys);
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private static boolean takes(String action, String key) {
        for (JsonNode taken : TABLE.path("actions").path(action == null ? "" : action)) {
            if (key.equals(taken.asText())) {
                return true;
            }
        }
        return false;
    }

    /** The tags a body member holds, by the element its list wraps each tag in. */
    private static Map<String, String> bodyTags(JsonNode member, Supplier<String> body) {
        String path = member.path("member").asText();
        int open = path.indexOf('[');
        if (open < 0 || !member.has("key") || !member.has("value")) {
            return Map.of();
        }
        String element = path.substring(open + 1, path.length() - 1);
        return XmlParser.extractPairs(body.get(), element.isEmpty() ? "member" : element,
                member.path("key").asText(), member.path("value").asText());
    }
}
