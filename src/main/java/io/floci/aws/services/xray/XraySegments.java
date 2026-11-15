package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a segment document Floci indexes: one entry per segment and subsegment, flattened,
 * with the identifiers, timing, outcome flags and annotations the summaries and the service graph
 * are built from.
 *
 * <p>A document holds a root segment and, nested under {@code subsegments}, the calls it made.
 * Floci keeps the documents verbatim, so every reader walks them here rather than parsing twice.
 */
final class XraySegments {

    /** One segment or subsegment, as far as X-Ray's own readers need it. */
    record Node(String traceId, String id, String parentId, String name, double startTime,
                double endTime, boolean fault, boolean error, boolean throttle, Integer httpStatus,
                String httpMethod, String httpUrl, boolean hasAws, Map<String, Object> annotations) {

        double duration() {
            return endTime - startTime;
        }

        /** What the service graph calls this node's type, as far as a document can tell. */
        String type() {
            if (hasAws) {
                return null;
            }
            return httpStatus != null || httpUrl != null ? "client" : null;
        }
    }

    private XraySegments() {
    }

    /** Every segment and subsegment a document holds, parents before children. */
    static List<Node> flatten(String document, ObjectMapperHolder mapper) {
        List<Node> nodes = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.read(document);
        } catch (Exception e) {
            return nodes;
        }
        collect(root, null, null, nodes);
        return nodes;
    }

    private static void collect(JsonNode node, String traceId, String parentId, List<Node> out) {
        if (!node.isObject()) {
            return;
        }
        String resolvedTraceId = node.path("trace_id").asText(traceId == null ? "" : traceId);
        String id = node.path("id").asText("");
        double start = node.path("start_time").asDouble(Double.NaN);
        double end = node.path("end_time").asDouble(Double.NaN);
        if (!id.isEmpty() && !Double.isNaN(start) && !Double.isNaN(end) && end >= start) {
            JsonNode http = node.path("http");
            JsonNode response = http.path("response");
            JsonNode request = http.path("request");
            out.add(new Node(resolvedTraceId, id,
                    node.path("parent_id").asText(parentId == null ? "" : parentId),
                    node.path("name").asText(""), start, end,
                    node.path("fault").asBoolean(false), node.path("error").asBoolean(false),
                    node.path("throttle").asBoolean(false),
                    response.has("status") ? Integer.valueOf(response.path("status").asInt()) : null,
                    request.path("method").asText(null), request.path("url").asText(null),
                    node.has("aws"), annotations(node.path("annotations"))));
        }
        for (JsonNode child : node.path("subsegments")) {
            collect(child, resolvedTraceId, id, out);
        }
    }

    /**
     * An annotation keeps the JSON type a segment gave it, because {@code AnnotationValue} answers
     * a number as {@code NumberValue} and a boolean as {@code BooleanValue}. A list or an object
     * is not an annotation AWS accepts, so it is dropped rather than flattened into text.
     */
    private static Map<String, Object> annotations(JsonNode node) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    annotations.put(entry.getKey(), value.asText());
                } else if (value.isNumber()) {
                    annotations.put(entry.getKey(), value.numberValue());
                } else if (value.isBoolean()) {
                    annotations.put(entry.getKey(), value.booleanValue());
                }
            });
        }
        return annotations;
    }

    /** Reads a document's JSON; kept behind an interface so this class stays free of Jackson wiring. */
    interface ObjectMapperHolder {
        JsonNode read(String document);
    }
}
