package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The input shapes of a service's operations, as a {@code tools/aws/regen_*_shapes.py} generator
 * vendors them from botocore: each operation's input shape, and for every shape reachable from one
 * its type and the shapes of its structure members, list elements or map values. A service's
 * request check walks a body against it with its own protocol's messages.
 */
public record RequestShapeModel(Map<String, String> operations, Map<String, Shape> shapes) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One shape: its type, the shape of each structure member, or the list element or map value shape. */
    public record Shape(String type, Map<String, String> members, String element) {
    }

    /** The vendored resource {@code name}; {@code description} names it in a failure. */
    public static RequestShapeModel load(String name, String description) {
        return VendoredResources.read(name, description, in -> parse(MAPPER.readTree(in)));
    }

    public static RequestShapeModel parse(JsonNode root) {
        Map<String, String> operations = new HashMap<>();
        root.path("operations").properties().forEach(op -> operations.put(op.getKey(), op.getValue().asText()));
        Map<String, Shape> shapes = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.path("shapes").properties()) {
            JsonNode node = entry.getValue();
            Map<String, String> members = new LinkedHashMap<>();
            node.path("members").properties().forEach(m -> members.put(m.getKey(), m.getValue().asText()));
            String element = node.hasNonNull("member") ? node.get("member").asText() : node.path("value").asText(null);
            shapes.put(entry.getKey(), new Shape(node.path("type").asText(), Collections.unmodifiableMap(members),
                    element));
        }
        return new RequestShapeModel(Map.copyOf(operations), Map.copyOf(shapes));
    }
}
