package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Some DynamoDB operations return the top-level attributes and every map in a new random order
 * on each call. Each map is drawn on its own. Sets and lists keep their order.
 */
final class DynamoDbAttributeOrder {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private DynamoDbAttributeOrder() {
    }

    static JsonNode shuffled(JsonNode item, boolean enabled) {
        if (!enabled || item == null || !item.isObject()) {
            return item;
        }
        return shuffleFields((ObjectNode) item);
    }

    private static ObjectNode shuffleFields(ObjectNode node) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.size());
        node.properties().forEach(entries::add);
        Collections.shuffle(entries, ThreadLocalRandom.current());
        ObjectNode copy = FACTORY.objectNode();
        for (Map.Entry<String, JsonNode> entry : entries) {
            copy.set(entry.getKey(), shuffleValue(entry.getValue()));
        }
        return copy;
    }

    private static JsonNode shuffleValue(JsonNode value) {
        if (value.isObject() && value.size() == 1) {
            JsonNode map = value.get("M");
            if (map != null && map.isObject()) {
                return FACTORY.objectNode().set("M", shuffleFields((ObjectNode) map));
            }
            JsonNode list = value.get("L");
            if (list != null && list.isArray()) {
                ArrayNode copy = FACTORY.arrayNode(list.size());
                for (JsonNode element : list) {
                    copy.add(shuffleValue(element));
                }
                return FACTORY.objectNode().set("L", copy);
            }
        }
        return value;
    }
}
