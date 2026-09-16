package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// UPDATED_NEW and UPDATED_OLD return the value at every path the update touched, whether
// it changed or not. Nested paths come back as a fragment along the path, and touched list
// elements are packed in index order.
final class DynamoDbUpdatedAttributes {

    private DynamoDbUpdatedAttributes() {}

    static ObjectNode collect(List<DynamoDbService.TouchedPath> touched, boolean newValues) {
        var root = new Node();
        for (var path : touched) {
            var value = newValues ? path.newValue() : path.oldValue();
            if (value != null) {
                root.insert(path.tokens(), 0, value);
            }
        }
        var out = JsonNodeFactory.instance.objectNode();
        root.mapChildren.forEach((name, child) -> out.set(name, child.render()));
        return out;
    }

    private static final class Node {
        private JsonNode whole;
        private final Map<String, Node> mapChildren = new LinkedHashMap<>();
        private final TreeMap<Long, Node> listChildren = new TreeMap<>();

        void insert(List<Object> tokens, int i, JsonNode value) {
            if (whole != null) {
                return;
            }
            if (i == tokens.size()) {
                whole = value;
                mapChildren.clear();
                listChildren.clear();
                return;
            }
            var child = tokens.get(i) instanceof Long index
                    ? listChildren.computeIfAbsent(index, ignored -> new Node())
                    : mapChildren.computeIfAbsent((String) tokens.get(i), ignored -> new Node());
            child.insert(tokens, i + 1, value);
        }

        JsonNode render() {
            if (whole != null) {
                return whole;
            }
            var out = JsonNodeFactory.instance.objectNode();
            if (!listChildren.isEmpty()) {
                var list = out.putArray("L");
                listChildren.values().forEach(child -> list.add(child.render()));
            } else {
                var map = out.putObject("M");
                mapChildren.forEach((name, child) -> map.set(name, child.render()));
            }
            return out;
        }
    }
}
