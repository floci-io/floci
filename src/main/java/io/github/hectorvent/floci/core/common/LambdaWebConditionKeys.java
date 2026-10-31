package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lambda Web Functions authType condition keys each action takes, and the request members that
 * carry the request key, generated from the AWS Service Reference and pinned botocore by
 * {@code tools/aws/regen_lambda_web_condition_keys.py}.
 */
final class LambdaWebConditionKeys {

    private static final JsonNode TABLE = VendoredResources.read("aws/lambda-web-condition-keys.json",
            "Lambda Web condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                if (!document.path("actions").isObject() || document.path("actions").isEmpty()
                        || !document.path("members").isObject() || document.path("members").isEmpty()) {
                    throw new IllegalStateException("Malformed Lambda Web condition key table");
                }
                return document;
            });

    private LambdaWebConditionKeys() {}

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
}
