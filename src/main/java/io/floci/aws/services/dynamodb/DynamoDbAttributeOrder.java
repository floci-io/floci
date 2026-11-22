package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
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
        int n = node.size();
        @SuppressWarnings("unchecked")
        Map.Entry<String, JsonNode>[] entries = new Map.Entry[n];
        int i = 0;
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            entries[i++] = entry;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int j = n - 1; j > 0; j--) {
            int k = random.nextInt(j + 1);
            Map.Entry<String, JsonNode> swap = entries[j];
            entries[j] = entries[k];
            entries[k] = swap;
        }
        Map<String, JsonNode> map = new LinkedHashMap<>((int) (n / 0.75f) + 1);
        for (Map.Entry<String, JsonNode> entry : entries) {
            map.put(entry.getKey(), shuffleValue(entry.getValue()));
        }
        return new ObjectNode(FACTORY, map);
    }

    private static ObjectNode wrap(String key, JsonNode value) {
        Map<String, JsonNode> map = new LinkedHashMap<>(2);
        map.put(key, value);
        return new ObjectNode(FACTORY, map);
    }

    private static JsonNode shuffleValue(JsonNode value) {
        if (value.isObject() && value.size() == 1) {
            JsonNode map = value.get("M");
            if (map != null && map.isObject()) {
                return wrap("M", shuffleFields((ObjectNode) map));
            }
            JsonNode list = value.get("L");
            if (list != null && list.isArray()) {
                ArrayNode copy = FACTORY.arrayNode(list.size());
                for (JsonNode element : list) {
                    copy.add(shuffleValue(element));
                }
                return wrap("L", copy);
            }
        }
        return value;
    }
}
