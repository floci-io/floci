package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rejects a DynamoDB request member of the wrong JSON type with the SerializationException
 * DynamoDB answers before it runs the operation. The input shapes come from the vendored
 * {@code aws/dynamodb-request-shapes.json}, generated from botocore's DynamoDB model by
 * {@code tools/aws/regen_dynamodb_shapes.py}.
 *
 * <p>AWS reads the body in order and reports the first bad member, without naming it. A JSON
 * null counts as an absent member, and a member the model does not know is ignored.
 */
public final class DynamoDbRequestShapes {

    static final String RESOURCE_NAME = "aws/dynamodb-request-shapes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OBJECT_NOT_EXPECTED = "Start of structure or map found where not expected";
    private static final String UNKNOWN_ERROR = "UnknownError";
    private static final String UNEXPECTED_FIELD_TYPE = "Unexpected field type";
    private static final String UNEXPECTED_VALUE_TYPE = "Unexpected value type in payload";

    record Shape(String type, Map<String, String> members, String element) {
    }

    record Model(Map<String, String> operations, Map<String, Shape> shapes) {
    }

    private static final class Holder {
        static final Model MODEL = load();
    }

    private DynamoDbRequestShapes() {
    }

    /** Checks a DynamoDB request body against the input shape of {@code action}, if the model has one. */
    public static void check(String action, JsonNode request) {
        check(Holder.MODEL, action, request);
    }

    static void check(Model model, String action, JsonNode request) {
        String input = model.operations().get(action);
        if (input != null) {
            checkValue(model, model.shapes().get(input), request, true);
        }
    }

    private static void checkValue(Model model, Shape shape, JsonNode value, boolean member) {
        if (value.isNull()) {
            return;
        }
        switch (shape.type()) {
            case "structure" -> checkStructure(model, shape, value, member);
            case "list" -> checkList(model, shape, value, member);
            case "map" -> checkMap(model, shape, value, member);
            default -> checkScalar(shape.type(), value);
        }
    }

    private static void checkStructure(Model model, Shape shape, JsonNode value, boolean member) {
        if (value.isArray()) {
            throw serialization(UNKNOWN_ERROR);
        }
        if (!value.isObject()) {
            throw serialization(member ? UNEXPECTED_FIELD_TYPE : UNEXPECTED_VALUE_TYPE);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null) {
                checkValue(model, model.shapes().get(memberShape), field.getValue(), true);
            }
        }
    }

    private static void checkList(Model model, Shape shape, JsonNode value, boolean member) {
        if (value.isObject()) {
            throw serialization(OBJECT_NOT_EXPECTED);
        }
        if (!value.isArray()) {
            throw serialization(member ? UNEXPECTED_FIELD_TYPE : UNKNOWN_ERROR);
        }
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item, false);
        }
    }

    private static void checkMap(Model model, Shape shape, JsonNode value, boolean member) {
        if (value.isArray()) {
            throw serialization(UNKNOWN_ERROR);
        }
        if (!value.isObject()) {
            throw serialization(member ? UNEXPECTED_FIELD_TYPE : UNKNOWN_ERROR);
        }
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item, false);
        }
    }

    private static void checkScalar(String type, JsonNode value) {
        if (value.isObject()) {
            throw serialization(OBJECT_NOT_EXPECTED);
        }
        if (value.isArray()) {
            throw serialization(UNKNOWN_ERROR);
        }
        switch (type) {
            case "string" -> requireToken(value.isTextual(), value, "String");
            case "integer" -> requireToken(value.isNumber(), value, "Integer");
            case "long" -> requireToken(value.isNumber(), value, "Long");
            case "double" -> requireToken(value.isNumber(), value, "Double");
            case "timestamp" -> requireToken(value.isNumber(), value, "Date");
            case "boolean" -> checkBoolean(value);
            case "blob" -> {
                if (!value.isTextual() && !value.isBinary()) {
                    throw serialization("only base-64-encoded strings are convertible to bytes");
                }
            }
            default -> {
            }
        }
    }

    private static void checkBoolean(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText();
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                throw serialization("Unexpected token received from parser");
            }
            return;
        }
        requireToken(value.isBoolean(), value, "Boolean");
    }

    private static void requireToken(boolean accepted, JsonNode value, String target) {
        if (!accepted) {
            throw serialization(token(value) + " cannot be converted to " + target);
        }
    }

    private static String token(JsonNode value) {
        if (value.isIntegralNumber()) {
            return "NUMBER_VALUE";
        }
        if (value.isNumber()) {
            return "DECIMAL_VALUE";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
        }
        return "STRING_VALUE";
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    private static Model load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("DynamoDB request shape resource not found: " + RESOURCE_NAME);
            }
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the DynamoDB request shapes " + RESOURCE_NAME, e);
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
            String element = node.hasNonNull("member") ? node.get("member").asText() : node.path("value").asText(null);
            shapes.put(entry.getKey(), new Shape(node.path("type").asText(), members, element));
        }
        return new Model(operations, shapes);
    }
}
