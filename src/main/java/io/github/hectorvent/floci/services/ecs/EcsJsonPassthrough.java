package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the members of a request that Floci has no behaviour for, so a describe returns what the
 * caller registered instead of a subset of it.
 *
 * <p>ECS's task definition and service shapes carry a long tail of members that only have to
 * survive a round trip: {@code proxyConfiguration}, {@code linuxParameters}, {@code ulimits},
 * {@code resourceRequirements}, placement constraints and so on. A client that writes one and
 * reads it back, such as Terraform or CDK, treats a dropped member as drift and proposes the same
 * change on every plan. Capturing whatever the parser did not consume covers the whole tail at
 * once, including members AWS adds later.
 */
final class EcsJsonPassthrough {

    private EcsJsonPassthrough() {
    }

    /**
     * The members of {@code node} that are not in {@code consumed}, in the order they arrived, or
     * {@code null} when there are none.
     */
    static Map<String, Object> capture(JsonNode node, ObjectMapper objectMapper, Set<String> consumed) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (consumed.contains(field.getKey())) {
                continue;
            }
            result.put(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Writes the captured members onto a response node. A member the serializer already wrote from
     * a typed field wins, so a value Floci normalizes is never overwritten by the raw input.
     */
    static void write(ObjectNode target, Map<String, Object> passthrough, ObjectMapper objectMapper) {
        if (passthrough == null || passthrough.isEmpty()) {
            return;
        }
        passthrough.forEach((key, value) -> {
            if (!target.has(key)) {
                target.set(key, objectMapper.valueToTree(value));
            }
        });
    }
}
