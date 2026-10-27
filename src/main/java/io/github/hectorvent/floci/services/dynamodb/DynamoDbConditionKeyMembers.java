package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.VendoredResources;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Action coverage from AWS Service Reference and request member paths from pinned botocore. */
final class DynamoDbConditionKeyMembers {

    private static final JsonNode TABLE = VendoredResources.read("aws/dynamodb-condition-keys.json",
            "DynamoDB IAM condition keys", input -> {
                JsonNode document = new ObjectMapper().readTree(input);
                if (!document.path("actions").isObject() || document.path("actions").isEmpty()
                        || !document.path("members").isObject() || document.path("members").isEmpty()
                        || document.path("attributesToGetMinimum").asInt() < 1) {
                    throw new IllegalStateException("Malformed DynamoDB IAM condition key table");
                }
                return document;
            });

    private DynamoDbConditionKeyMembers() {}

    static int attributesToGetMinimum() {
        return TABLE.path("attributesToGetMinimum").intValue();
    }

    static boolean supports(String action, String key) {
        for (JsonNode condition : TABLE.path("actions").path(action == null ? "" : action)) {
            if (key.equals(condition.asText())) {
                return true;
            }
        }
        return false;
    }

    static List<JsonNode> values(String operation, String key, String member, JsonNode body) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode path : TABLE.path("members").path(operation).path(key)) {
            String[] steps = path.asText().split("\\.");
            if (member.equals(steps[steps.length - 1])) {
                collect(body, steps, 0, values);
            }
        }
        return values;
    }

    static JsonNode itemBody(String operation, String prefix, JsonNode body) {
        if (body == null || !body.isObject()) {
            return body;
        }
        Set<String> members = new LinkedHashSet<>();
        for (JsonNode paths : TABLE.path("members").path(operation)) {
            for (JsonNode path : paths) {
                String value = path.asText();
                if (value.startsWith(prefix)) {
                    members.add(value.substring(prefix.length()).split("\\.", 2)[0]);
                }
            }
        }
        ObjectNode item = body.deepCopy();
        return item.retain(members);
    }

    private static void collect(JsonNode node, String[] steps, int position, List<JsonNode> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (position == steps.length) {
            values.add(node);
        } else if ("*".equals(steps[position])) {
            node.forEach(child -> collect(child, steps, position + 1, values));
        } else {
            collect(node.get(steps[position]), steps, position + 1, values);
        }
    }
}
