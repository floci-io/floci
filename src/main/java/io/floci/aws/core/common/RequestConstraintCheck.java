package io.floci.aws.core.common;

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
        AwsException bounds(String path, String type, Long min, Long max, Number size);

        /** A string does not match the shape's {@code pattern}. */
        AwsException pattern(String path, String pattern);

        /** A value is not one of the shape's {@code values}. */
        AwsException enumValue(String path, List<String> values);

        /** The structure member at {@code path} is required but missing or null. */
        AwsException required(String path);
    }

    /** Standard member-constraint messages, using the service's error code and HTTP 400. */
    public static Messages standardMessages(String errorCode) {
        return new Messages() {
            @Override
            public AwsException type(String path, String type) {
                return invalid(path, "Member must be a " + type);
            }

            @Override
            public AwsException bounds(String path, String type, Long min, Long max, Number size) {
                boolean fractional = "float".equals(type) || "double".equals(type);
                String measure = fractional || "integer".equals(type) || "long".equals(type) ? "value" : "length";
                boolean below = min != null && (fractional ? size.doubleValue() < min : size.longValue() < min);
                return below
                        ? invalid(path, "Member must have " + measure + " greater than or equal to " + min)
                        : invalid(path, "Member must have " + measure + " less than or equal to " + max);
            }

            @Override
            public AwsException pattern(String path, String pattern) {
                return invalid(path, "must satisfy regular expression '" + pattern + "'");
            }

            @Override
            public AwsException enumValue(String path, List<String> values) {
                return invalid(path, "Member must satisfy enum value set: " + values);
            }

            @Override
            public AwsException required(String path) {
                return invalid(path, "Member must not be null");
            }

            private AwsException invalid(String path, String rule) {
                int dot = path.indexOf('.');
                String member = (dot < 0 ? "" : path.substring(dot + 1)).replace("[]", "");
                return new AwsException(errorCode,
                        "Invalid parameter at '" + member + "' failed to satisfy constraint: '" + rule + "'", 400);
            }
        };
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

    /** Whether the vendored model declares an input for {@code operation}. */
    public boolean hasOperation(String operation) {
        return model.path("operations").has(operation);
    }

    /** Checks {@code request}, the body of {@code operation}, against the operation's input shape. */
    public void check(String operation, JsonNode request) {
        checkValue(model.path("operations").path(operation).asText(), request, operation);
    }

    /** Whether the model holds the input shape of {@code operation}. */
    public boolean holds(String operation) {
        return model.path("operations").has(operation);
    }

    /** Checks one modeled value without treating it as a whole request. */
    public void checkShape(String shapeName, JsonNode value) {
        checkValue(shapeName, value, shapeName);
    }

    /** Checks a modeled request list's count without validating its members as one batch error. */
    public void checkListSize(String operation, String member, int size) {
        String input = model.path("operations").path(operation).asText();
        String list = model.path("shapes").path(input).path("members").path(member).asText();
        JsonNode shape = model.path("shapes").path(list);
        if (!"list".equals(shape.path("type").asText())) {
            throw new IllegalArgumentException(operation + "." + member + " is not a modeled list");
        }
        checkBounds(shape, operation + "." + member, "list", size);
    }

    private void checkValue(String name, JsonNode value, String path) {
        JsonNode shape = model.path("shapes").path(name);
        String type = shape.path("type").asText();
        boolean validType = value != null && switch (type) {
            case "structure", "map" -> value.isObject();
            case "list" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            case "long" -> value.isIntegralNumber() && value.canConvertToLong();
            case "float", "double" -> value.isNumber();
            default -> false;
        };
        if (!validType) {
            throw messages.type(path, type);
        }
        Number size = switch (type) {
            case "string" -> value.textValue().codePointCount(0, value.textValue().length());
            case "integer", "long" -> value.longValue();
            case "float", "double" -> value.doubleValue();
            default -> value.size();
        };
        checkBounds(shape, path, type, size);
        if (patterns.containsKey(name) && !patterns.get(name).matcher(value.asText()).matches()) {
            throw messages.pattern(path, VendoredPatterns.quoted(shape));
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

    private void checkBounds(JsonNode shape, String path, String type, Number size) {
        Long min = shape.has("min") ? shape.get("min").asLong() : null;
        Long max = shape.has("max") ? shape.get("max").asLong() : null;
        boolean outside;
        if ("float".equals(type) || "double".equals(type)) {
            double number = size.doubleValue();
            outside = !Double.isFinite(number) || min != null && number < min || max != null && number > max;
        } else {
            outside = min != null && size.longValue() < min || max != null && size.longValue() > max;
        }
        if (outside) {
            throw messages.bounds(path, type, min, max, size);
        }
    }
}
