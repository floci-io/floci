package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The service condition keys each action takes that Floci reads from the request or from the resource it acts on,
 * and the request members that carry them, generated from the AWS Service Reference and pinned botocore by
 * {@code tools/aws/regen_request_condition_keys.py}.
 */
final class RequestConditionKeys {

    private static final JsonNode TABLE = VendoredResources.read("aws/request-condition-keys.json",
            "request condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                if (!document.path("actions").isObject() || document.path("actions").isEmpty()
                        || !document.path("members").isObject() || document.path("members").isEmpty()) {
                    throw new IllegalStateException("Malformed request condition key table");
                }
                return document;
            });

    private RequestConditionKeys() {}

    static boolean takes(String action, String key) {
        for (JsonNode taken : TABLE.path("actions").path(action)) {
            if (key.equals(taken.asText())) {
                return true;
            }
        }
        return false;
    }

    /** The dotted paths of the request members that carry {@code key} for {@code action}; empty when none. */
    static List<String> members(String action, String key) {
        List<String> members = new ArrayList<>();
        TABLE.path("members").path(action).path(key).forEach(member -> members.add(member.asText()));
        return members;
    }

    /** The keys a request for {@code action} carries in its own members; empty when none. */
    static List<String> requestKeys(String action) {
        List<String> keys = new ArrayList<>();
        TABLE.path("members").path(action).properties().forEach(key -> keys.add(key.getKey()));
        return keys;
    }
}
