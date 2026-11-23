package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB stores the keys of a map, at any depth, in code point order when the value arrives
 * through the plain API. PartiQL literals keep the order they are written in. An update puts
 * the maps on its document path in code point order and leaves the others as they are.
 */
public final class DynamoDbMapOrder {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private DynamoDbMapOrder() {
    }

    /**
     * Returns a copy of an item, or of a map of attribute values such as ExpressionAttributeValues,
     * with every nested map sorted. The top-level order is kept. Input holding no map is returned as is.
     */
    public static JsonNode sortedItem(JsonNode item) {
        if (item == null || !item.isObject() || !holdsMap(item)) {
            return item;
        }
        return copyFields((ObjectNode) item, false);
    }

    /** Returns a copy of a legacy AttributeUpdates map with every nested map of each Value sorted. */
    public static JsonNode sortedAttributeUpdates(JsonNode updates) {
        if (updates == null || !updates.isObject() || !holdsMap(updates)) {
            return updates;
        }
        ObjectNode sorted = FACTORY.objectNode();
        for (Map.Entry<String, JsonNode> entry : updates.properties()) {
            JsonNode update = entry.getValue();
            JsonNode value = update.get("Value");
            sorted.set(entry.getKey(), value != null && holdsMap(value)
                    ? withMember(update, "Value", copyValue(value)) : update);
        }
        return sorted;
    }

    /** Returns a copy of {@code request} whose {@code operation.member} value has every nested map sorted. */
    static JsonNode withSortedMember(JsonNode request, String operation, String member) {
        JsonNode op = request.get(operation);
        if (op == null || !op.isObject()) {
            return request;
        }
        JsonNode value = op.get(member);
        JsonNode sorted = sortedItem(value);
        if (sorted == value) {
            return request;
        }
        return withMember(request, operation, withMember(op, member, sorted));
    }

    /** Returns a shallow copy of {@code node} with {@code name} set to {@code value}. */
    private static ObjectNode withMember(JsonNode node, String name, JsonNode value) {
        ObjectNode copy = FACTORY.objectNode();
        copy.setAll((ObjectNode) node);
        copy.set(name, value);
        return copy;
    }

    /** Puts the keys of one map in code point order, in place. Nested maps are not touched. */
    static void sortKeys(ObjectNode map) {
        if (map.size() < 2) {
            return;
        }
        List<String> names = names(map);
        List<String> sorted = DynamoDbSetOrder.byCodePoint(names);
        if (sorted.equals(names)) {
            return;
        }
        Map<String, JsonNode> fields = LinkedHashMap.newLinkedHashMap(names.size());
        for (Map.Entry<String, JsonNode> entry : map.properties()) {
            fields.put(entry.getKey(), entry.getValue());
        }
        map.removeAll();
        for (String name : sorted) {
            map.set(name, fields.get(name));
        }
    }

    private static List<String> names(ObjectNode node) {
        List<String> names = new ArrayList<>(node.size());
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean holdsMap(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (holdsMap(element)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if ("M".equals(entry.getKey()) || holdsMap(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode copyFields(ObjectNode node, boolean sortKeys) {
        List<String> names = names(node);
        Map<String, JsonNode> map = LinkedHashMap.newLinkedHashMap(names.size());
        for (String name : sortKeys ? DynamoDbSetOrder.byCodePoint(names) : names) {
            map.put(name, copyValue(node.get(name)));
        }
        return new ObjectNode(FACTORY, map);
    }

    private static JsonNode copyValue(JsonNode value) {
        if (!value.isObject() || value.size() != 1) {
            return value;
        }
        JsonNode map = value.get("M");
        if (map != null && map.isObject()) {
            return FACTORY.objectNode().set("M", copyFields((ObjectNode) map, true));
        }
        JsonNode list = value.get("L");
        if (list != null && list.isArray()) {
            ArrayNode copy = FACTORY.arrayNode(list.size());
            for (JsonNode element : list) {
                copy.add(copyValue(element));
            }
            return FACTORY.objectNode().set("L", copy);
        }
        return value;
    }
}
