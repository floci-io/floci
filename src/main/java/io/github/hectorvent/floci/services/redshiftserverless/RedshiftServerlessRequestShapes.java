package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.VendoredResources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a Redshift Serverless request against the constraints of its input shape: required
 * members, string length and pattern, enum values, number range and list size. The shapes come from
 * the vendored {@code aws/redshift-serverless-request-shapes.json}, generated from botocore's
 * Redshift Serverless model by {@code tools/aws/regen_redshift_serverless_shapes.py}.
 *
 * <p>A pattern must match the whole value, as AWS applies the unanchored ones such as the account id, and a
 * length counts Unicode code points, not UTF-16 units. A JSON null counts as an absent member and a member the model does not know is ignored. A
 * member of the wrong JSON type is left for {@link ServerlessSupport} to report when the handler
 * reads it, so this check only judges values it can read as the modeled type.
 */
final class RedshiftServerlessRequestShapes {

    static final String RESOURCE_NAME = "aws/redshift-serverless-request-shapes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Skipped because only ListUsageLimits answers InvalidPaginationException for out-of-range
     * pagination values, while the other lists use ValidationException; the services report both themselves.
     */
    private static final Set<String> PAGINATION_MEMBERS = Set.of("maxResults", "nextToken");

    record Shape(String type, Map<String, String> members, List<String> required, String element,
                 Long min, Long max, Pattern pattern, List<String> enumValues) {
    }

    record Model(Map<String, String> operations, Map<String, Shape> shapes) {
    }

    private static final class Holder {
        static final Model MODEL = VendoredResources.read(RESOURCE_NAME,
                "Redshift Serverless request shapes",
                in -> parse(MAPPER.readTree(in)));
    }

    private RedshiftServerlessRequestShapes() {
    }

    /** Throws a {@code ValidationException} when {@code request} breaks a constraint of {@code action}'s input shape. */
    static void check(String action, JsonNode request) {
        check(Holder.MODEL, action, request);
    }

    static void check(Model model, String action, JsonNode request) {
        String input = model.operations().get(action);
        if (input != null && request != null && request.isObject()) {
            checkStructure(model, model.shapes().get(input), request, "");
        }
    }

    private static void checkStructure(Model model, Shape shape, JsonNode value, String path) {
        for (String name : shape.required()) {
            if (value.path(name).isMissingNode() || value.get(name).isNull()) {
                throw ServerlessSupport.validation(path + name + " is required.");
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null && !field.getValue().isNull() && !PAGINATION_MEMBERS.contains(field.getKey())) {
                checkValue(model, model.shapes().get(memberShape), field.getValue(), path + field.getKey());
            }
        }
    }

    private static void checkValue(Model model, Shape shape, JsonNode value, String path) {
        switch (shape.type()) {
            case "structure" -> {
                if (value.isObject()) {
                    checkStructure(model, shape, value, path + ".");
                }
            }
            case "list" -> checkList(model, shape, value, path);
            case "string" -> {
                if (value.isTextual()) {
                    checkString(shape, value.textValue(), path);
                }
            }
            case "integer", "long" -> {
                if (value.isIntegralNumber()) {
                    checkNumber(shape, value.longValue(), path);
                }
            }
            default -> {
            }
        }
    }

    private static void checkList(Model model, Shape shape, JsonNode value, String path) {
        if (!value.isArray()) {
            return;
        }
        checkBounds(shape, value.size(), path, "size");
        Shape element = model.shapes().get(shape.element());
        int index = 0;
        for (JsonNode item : value) {
            if (!item.isNull()) {
                checkValue(model, element, item, path + "[" + index + "]");
            }
            index++;
        }
    }

    private static void checkString(Shape shape, String value, String path) {
        checkBounds(shape, value.codePointCount(0, value.length()), path, "length");
        if (shape.enumValues() != null && !shape.enumValues().contains(value)) {
            throw ServerlessSupport.validation(path + " must be one of " + shape.enumValues() + ".");
        }
        if (shape.pattern() != null && !shape.pattern().matcher(value).matches()) {
            throw ServerlessSupport.validation(path + " must match the pattern " + shape.pattern().pattern() + ".");
        }
    }

    private static void checkNumber(Shape shape, long value, String path) {
        checkBounds(shape, value, path, "value");
    }

    private static void checkBounds(Shape shape, long actual, String path, String what) {
        if (shape.min() != null && actual < shape.min()) {
            throw ServerlessSupport.validation(path + " must have " + what + " at least " + shape.min() + ".");
        }
        if (shape.max() != null && actual > shape.max()) {
            throw ServerlessSupport.validation(path + " must have " + what + " at most " + shape.max() + ".");
        }
    }

    static Model parse(JsonNode root) {
        Map<String, String> operations = new HashMap<>();
        root.path("operations").fields().forEachRemaining(op -> operations.put(op.getKey(), op.getValue().asText()));
        Map<String, Shape> shapes = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = root.path("shapes").fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode node = entry.getValue();
            Map<String, String> members = new LinkedHashMap<>();
            node.path("members").fields().forEachRemaining(m -> members.put(m.getKey(), m.getValue().asText()));
            shapes.put(entry.getKey(), new Shape(
                    node.path("type").asText(),
                    members,
                    strings(node.path("required")),
                    node.hasNonNull("member") ? node.get("member").asText() : null,
                    node.hasNonNull("min") ? node.get("min").asLong() : null,
                    node.hasNonNull("max") ? node.get("max").asLong() : null,
                    node.hasNonNull("pattern") ? Pattern.compile(node.get("pattern").asText()) : null,
                    node.has("enum") ? strings(node.get("enum")) : null));
        }
        return new Model(operations, shapes);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }
}
