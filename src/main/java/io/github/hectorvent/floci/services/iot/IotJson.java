package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON request-parsing and response-building helpers for the IoT REST controllers.
 */
final class IotJson {

    private IotJson() {
    }

    static JsonNode parseBody(ObjectMapper objectMapper, String body) throws IOException {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    static String text(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return (child.isMissingNode() || child.isNull()) ? null : child.asText();
    }

    static Long optionalLong(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return (child.isMissingNode() || child.isNull()) ? null : child.asLong();
    }

    static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> values.add(n.asText()));
        }
        return values;
    }

    static Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    static void putToken(ObjectNode node, String field, String token) {
        if (token != null) {
            node.put(field, token);
        } else {
            node.putNull(field);
        }
    }

    static void putEpoch(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.toEpochMilli() / 1000.0);
        }
    }
}
