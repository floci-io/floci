package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks a JSON request against the constraints a {@code tools/aws/regen_*_shapes.py} generator
 * vendors from botocore through {@code request_shapes.build_constraints}: each member's type, the
 * members a structure requires, a string's length, a list's or map's size, a number's range, a
 * pattern and an enum. A service supplies its own {@link Messages}, so each answers with its own
 * error code and wording.
 *
 * <p>Paths start with the operation name: {@code PutReplicationConfiguration.replicationConfiguration.rules[]},
 * with {@code []} for a list element and {@code .key} for a map key.
 */
public final class RequestConstraintCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How a service words each kind of violation. */
    public interface Messages {
        /** {@code value} at {@code path} is not of the shape's JSON {@code type}. */
        AwsException type(String path, String type);

        /** {@code size} (a length, a count or a number's value) is outside {@code min}..{@code max}. */
        AwsException bounds(String path, String type, Long min, Long max, long size);

        /** A string does not match the shape's {@code pattern}. */
        AwsException pattern(String path, String pattern);

        /** A value is not one of the shape's {@code values}. */
        AwsException enumValue(String path, List<String> values);

        /** The structure member at {@code path} is required but missing or null. */
        AwsException required(String path);
    }

    private final JsonNode model;
    private final Map<String, Pattern> patterns = new HashMap<>();
    private final Messages messages;

    private RequestConstraintCheck(JsonNode model, Messages messages) {
        this.model = model;
        this.messages = messages;
        for (Map.Entry<String, JsonNode> shape : model.path("shapes").properties()) {
            if (shape.getValue().has("pattern")) {
                patterns.put(shape.getKey(), VendoredPatterns.compile(shape.getValue().get("pattern").asText()));
            }
        }
    }

    /** The vendored resource {@code name}; {@code description} names it in a failure. */
    public static RequestConstraintCheck load(String name, String description, Messages messages) {
        return VendoredResources.read(name, description,
                input -> new RequestConstraintCheck(MAPPER.readTree(input), messages));
    }

    public static RequestConstraintCheck of(JsonNode model, Messages messages) {
        return new RequestConstraintCheck(model, messages);
    }

    /** Checks {@code request}, the body of {@code operation}, against the operation's input shape. */
    public void check(String operation, JsonNode request) {
        checkValue(model.path("operations").path(operation).asText(), request, operation);
    }

    private void checkValue(String name, JsonNode value, String path) {
        JsonNode shape = model.path("shapes").path(name);
        String type = shape.path("type").asText();
        boolean validType = value != null && switch (type) {
            case "structure", "map" -> value.isObject();
            case "list" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer", "long" -> value.isIntegralNumber() && value.canConvertToLong();
            default -> false;
        };
        if (!validType) {
            throw messages.type(path, type);
        }
        long size = switch (type) {
            case "string" -> value.textValue().codePointCount(0, value.textValue().length());
            case "integer", "long" -> value.longValue();
            default -> value.size();
        };
        Long min = shape.has("min") ? shape.get("min").asLong() : null;
        Long max = shape.has("max") ? shape.get("max").asLong() : null;
        if (min != null && size < min || max != null && size > max) {
            throw messages.bounds(path, type, min, max, size);
        }
        if (patterns.containsKey(name) && !patterns.get(name).matcher(value.asText()).matches()) {
            throw messages.pattern(path, shape.get("pattern").asText());
        }
        if (shape.has("enum")) {
            boolean known = false;
            List<String> values = new ArrayList<>();
            for (JsonNode choice : shape.get("enum")) {
                known |= choice.equals(value);
                values.add(choice.asText());
            }
            if (!known) {
                throw messages.enumValue(path, values);
            }
        }
        switch (type) {
            case "structure" -> {
                for (JsonNode required : shape.path("required")) {
                    if (!value.hasNonNull(required.asText())) {
                        throw messages.required(path + "." + required.asText());
                    }
                }
                for (Map.Entry<String, JsonNode> field : value.properties()) {
                    JsonNode member = shape.path("members").get(field.getKey());
                    if (member != null && !field.getValue().isNull()) {
                        checkValue(member.asText(), field.getValue(), path + "." + field.getKey());
                    }
                }
            }
            case "list" -> {
                for (JsonNode item : value) {
                    checkValue(shape.get("member").asText(), item, path + "[]");
                }
            }
            case "map" -> {
                for (Map.Entry<String, JsonNode> field : value.properties()) {
                    checkValue(shape.get("key").asText(), TextNode.valueOf(field.getKey()), path + ".key");
                    checkValue(shape.get("value").asText(), field.getValue(), path + "." + field.getKey());
                }
            }
            default -> {
            }
        }
    }
}
