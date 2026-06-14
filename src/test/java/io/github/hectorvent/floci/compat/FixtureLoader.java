package io.github.hectorvent.floci.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Loads AWS API fixtures captured against real AWS endpoints. Each fixture is
 * the canonical wire-format response that floci must replicate (or, for error
 * fixtures, the canonical exception name + HTTP status code).
 *
 * <p>Fixtures live in {@code src/test/resources/fixtures/<service>/<name>.json}
 * and follow this structure:
 * <pre>{@code
 * {
 *   "name": "sign-up.happy",
 *   "capturedAt": "...",
 *   "request":  { ... raw AWS SDK request body ... },
 *   "response": { ... raw AWS SDK response body, or null if error ... },
 *   "error":    { "name": "...", "message": "...", "$metadata": {...} }  // null if success
 * }
 * }</pre>
 *
 * <p>Use {@link #normalize(JsonNode)} before equality comparisons to strip
 * volatile fields (RequestId, UserSub, timestamps, opaque Session tokens).
 */
public final class FixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "requestId", "RequestId", "x-amzn-RequestId",
            "UserSub",
            "Session",
            "Timestamp", "SentAt", "capturedAt",
            "$metadata"
    );

    private FixtureLoader() {}

    /** Load a fixture by name (without the .json extension). */
    public static JsonNode load(String name) throws IOException {
        String path = "/fixtures/aws-cognito/" + name + ".json";
        try (InputStream in = FixtureLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Fixture not found on classpath: " + path);
            }
            return MAPPER.readTree(in);
        }
    }

    /**
     * Recursively remove volatile fields (RequestId, UserSub, timestamps, Session)
     * so two responses can be structurally compared without false negatives.
     */
    public static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            for (JsonNode child : node) normalize(child);
            return node;
        }
        if (node instanceof ObjectNode obj) {
            Iterator<String> fields = obj.fieldNames();
            List<String> toRemove = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                String f = fields.next();
                if (VOLATILE_FIELDS.contains(f)) toRemove.add(f);
            }
            toRemove.forEach(obj::remove);
            obj.fieldNames().forEachRemaining(f -> normalize(obj.get(f)));
        }
        return node;
    }
}
