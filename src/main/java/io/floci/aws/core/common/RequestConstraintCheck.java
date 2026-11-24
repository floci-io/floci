package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks a JSON request against the constraints a {@code tools/aws/regen_*_shapes.py} generator
 * vendors from botocore through {@code request_shapes.build_constraints}: each member's type, the
 * members a structure requires, a string's length, a list's or map's size, a number's range, a
 * pattern and an enum. A service supplies its own {@link Messages}, so each answers with its own
 * error code and wording.
 *
 * <p>The check answers either way. {@link #check} throws the first violation in the service's own
 * wording, {@link #violations} collects all of them in the Smithy wording.
 *
 * <p>Paths start with the operation name: {@code PutReplicationConfiguration.replicationConfiguration.rules[]},
 * with {@code []} for a list element and {@code .key} for a map key.
 */
public final class RequestConstraintCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SCALAR_TYPES =
            Set.of("string", "integer", "long", "boolean", "float", "double");

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
    private final Set<String> wordable = ConcurrentHashMap.newKeySet();

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

    /**
     * Every constraint {@code request} breaks for {@code operation}, worded as a Smithy validation
     * error and in the model's member order, to be thrown with
     * {@link SmithyValidationErrors#exception(String, List)}. Paths join the member names with a dot,
     * each with its first letter lower-cased. A shape marked {@code sensitive} leaves its value out
     * of the wording. A value of the wrong JSON type is thrown at once through {@link Messages#type}.
     *
     * @throws IllegalArgumentException when the model holds no input for {@code operation}
     * @throws IllegalStateException when the operation's input holds a shape this walk cannot word yet
     */
    public List<String> violations(String operation, JsonNode request) {
        if (!hasOperation(operation)) {
            throw new IllegalArgumentException("No input shape for " + operation);
        }
        requireWordable(operation);
        JsonNode body = request == null || request.isNull() ? MAPPER.createObjectNode() : request;
        List<String> found = new ArrayList<>();
        collectStructure(model.path("operations").get(operation).asText(), body, "", operation, found);
        return found;
    }

    private void requireWordable(String operation) {
        if (wordable.contains(operation)) {
            return;
        }
        Set<String> seen = new HashSet<>();
        String input = model.path("operations").get(operation).asText();
        seen.add(input);
        for (Map.Entry<String, JsonNode> member : model.path("shapes").path(input).path("members").properties()) {
            requireWordableMember(operation, member.getKey(), member.getValue().asText(), seen);
        }
        wordable.add(operation);
    }

    private void requireWordableMember(String operation, String member, String name, Set<String> seen) {
        JsonNode shape = model.path("shapes").path(name);
        String type = shape.path("type").asText();
        switch (type) {
            case "string", "integer", "long", "float", "double", "boolean" -> {
            }
            case "structure" -> {
                if (seen.add(name)) {
                    for (Map.Entry<String, JsonNode> field : shape.path("members").properties()) {
                        requireWordableMember(operation, member + "." + field.getKey(),
                                field.getValue().asText(), seen);
                    }
                }
            }
            case "list" -> {
                JsonNode element = model.path("shapes").path(shape.path("member").asText());
                String elementType = element.path("type").asText();
                boolean bounded = element.has("min") || element.has("max");
                if (!SCALAR_TYPES.contains(elementType) || bounded && !"string".equals(elementType)) {
                    throw unwordable(operation, member + "[]", elementType);
                }
                if (element.path("sensitive").asBoolean(false)) {
                    throw unwordable(operation, member + "[]", "sensitive " + elementType);
                }
            }
            case "map" -> {
                for (String side : List.of("key", "value")) {
                    JsonNode sideShape = model.path("shapes").path(shape.path(side).asText());
                    String sideType = sideShape.path("type").asText();
                    if (!"string".equals(sideType)) {
                        throw unwordable(operation, member + "." + side, sideType);
                    }
                    if (sideShape.path("sensitive").asBoolean(false)) {
                        throw unwordable(operation, member + "." + side, "sensitive " + sideType);
                    }
                }
            }
            default -> throw unwordable(operation, member, type);
        }
    }

    private static IllegalStateException unwordable(String operation, String member, String type) {
        return new IllegalStateException(operation + "'s " + member + " is a " + type
                + ", which this check does not word yet");
    }

    private void collectStructure(String name, JsonNode value, String path, String errorPath, List<String> found) {
        JsonNode shape = model.path("shapes").path(name);
        if (!value.isObject()) {
            throw messages.type(errorPath, "structure");
        }
        Set<String> required = new HashSet<>();
        shape.path("required").forEach(member -> required.add(member.asText()));
        for (Map.Entry<String, JsonNode> member : shape.path("members").properties()) {
            String field = member.getKey();
            String memberPath = (path.isEmpty() ? "" : path + ".") + lowerFirst(field);
            JsonNode child = value.get(field);
            if (child == null || child.isNull()) {
                if (required.contains(field)) {
                    found.add(SmithyValidationErrors.notNull(memberPath));
                }
                continue;
            }
            collectValue(member.getValue().asText(), child, memberPath, errorPath + "." + field, found);
        }
    }

    private void collectValue(String name, JsonNode value, String path, String errorPath, List<String> found) {
        JsonNode shape = model.path("shapes").path(name);
        String type = shape.path("type").asText();
        SmithyValidationErrors.Violations form = formOf(shape);
        switch (type) {
            case "structure" -> collectStructure(name, value, path, errorPath, found);
            case "string" -> {
                if (!value.isTextual()) {
                    throw messages.type(errorPath, type);
                }
                collectString(name, shape, value.textValue(), path, form, found);
            }
            case "integer", "long" -> collectInteger(shape, type, value, path, errorPath, form, found);
            case "float", "double" -> {
                if (!value.isNumber()) {
                    throw messages.type(errorPath, type);
                }
                BigDecimal number = value.decimalValue();
                if (shape.has("min") && number.compareTo(BigDecimal.valueOf(shape.get("min").asLong())) < 0) {
                    found.add(form.valueTooSmall(path, value.asText(), shape.get("min").asLong()));
                } else if (shape.has("max") && number.compareTo(BigDecimal.valueOf(shape.get("max").asLong())) > 0) {
                    found.add(form.valueTooLarge(path, value.asText(), shape.get("max").asLong()));
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    throw messages.type(errorPath, type);
                }
            }
            case "list" -> collectList(shape, value, path, errorPath, form, found);
            case "map" -> collectMap(shape, value, path, errorPath, form, found);
            default -> throw unwordable(errorPath, path, type);
        }
    }

    private void collectInteger(JsonNode shape, String type, JsonNode value, String path, String errorPath,
                                SmithyValidationErrors.Violations form, List<String> found) {
        if (!value.isIntegralNumber()) {
            throw messages.type(errorPath, type);
        }
        BigInteger number = value.bigIntegerValue();
        if (shape.has("min") && number.compareTo(BigInteger.valueOf(shape.get("min").asLong())) < 0) {
            found.add(form.valueTooSmall(path, value.asText(), shape.get("min").asLong()));
        } else if (shape.has("max") && number.compareTo(BigInteger.valueOf(shape.get("max").asLong())) > 0) {
            found.add(form.valueTooLarge(path, value.asText(), shape.get("max").asLong()));
        } else if (!("integer".equals(type) ? value.canConvertToInt() : value.canConvertToLong())) {
            throw messages.type(errorPath, type);
        }
    }

    private void collectString(String name, JsonNode shape, String text, String path,
                               SmithyValidationErrors.Violations form, List<String> found) {
        String length = form.lengthOutOfRange(path, text, shape.path("min").asInt(0),
                shape.has("max") ? shape.get("max").asInt() : Integer.MAX_VALUE);
        if (length != null) {
            found.add(length);
        }
        if (patternBroken(name, text)) {
            found.add(form.pattern(path, text, VendoredPatterns.quoted(shape)));
        }
        if (shape.has("enum") && !inEnum(shape, text)) {
            found.add(form.notInEnum(text, path, enumValues(shape)));
        }
    }

    private void collectList(JsonNode shape, JsonNode value, String path, String errorPath,
                             SmithyValidationErrors.Violations form, List<String> found) {
        if (!value.isArray()) {
            throw messages.type(errorPath, "list");
        }
        String listText = elementsText(value);
        String size = sizeViolation(shape, value.size());
        if (size != null) {
            found.add(form.constraint(path, listText, size));
        }
        String memberName = shape.path("member").asText();
        boolean broken = false;
        for (JsonNode item : value) {
            broken |= item.isNull() || scalarBroken(memberName, item, errorPath + "[]");
        }
        if (broken) {
            found.add(form.constraint(path, listText, "Member must satisfy constraint: " + nullableRules(memberName)));
        }
    }

    private void collectMap(JsonNode shape, JsonNode value, String path, String errorPath,
                            SmithyValidationErrors.Violations form, List<String> found) {
        if (!value.isObject()) {
            throw messages.type(errorPath, "map");
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            entries.add(entry.getKey() + "=" + elementText(entry.getValue()));
        }
        String mapText = "{" + String.join(", ", entries) + "}";
        String size = sizeViolation(shape, value.size());
        if (size != null) {
            found.add(form.constraint(path, mapText, size));
        }
        String keyName = shape.path("key").asText();
        String valueName = shape.path("value").asText();
        boolean brokenKey = false;
        boolean brokenValue = false;
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            brokenKey |= scalarBroken(keyName, TextNode.valueOf(entry.getKey()), errorPath + ".key");
            brokenValue |= entry.getValue().isNull()
                    || scalarBroken(valueName, entry.getValue(), errorPath + "." + entry.getKey());
        }
        if (brokenKey) {
            found.add(form.mapKeys(path, mapText, rulesOf(keyName)));
        }
        if (brokenValue) {
            found.add(form.mapValues(path, mapText, nullableRules(valueName)));
        }
    }

    /** Whether a non-null scalar breaks its shape's rules. A value of another JSON type is a type error. */
    private boolean scalarBroken(String name, JsonNode item, String errorPath) {
        JsonNode shape = model.path("shapes").path(name);
        String type = shape.path("type").asText();
        boolean fits = switch (type) {
            case "string" -> item.isTextual();
            case "boolean" -> item.isBoolean();
            case "integer" -> item.isIntegralNumber() && item.canConvertToInt();
            case "long" -> item.isIntegralNumber() && item.canConvertToLong();
            default -> item.isNumber();
        };
        if (!fits) {
            throw messages.type(errorPath, type);
        }
        if (!"string".equals(type)) {
            return false;
        }
        String text = item.textValue();
        return lengthBroken(shape, text) || patternBroken(name, text)
                || shape.has("enum") && !inEnum(shape, text);
    }

    private static boolean lengthBroken(JsonNode shape, String text) {
        int length = text.codePointCount(0, text.length());
        return shape.has("min") && length < shape.get("min").asInt()
                || shape.has("max") && length > shape.get("max").asInt();
    }

    private boolean patternBroken(String name, String text) {
        return patterns.containsKey(name) && !patterns.get(name).matcher(text).matches();
    }

    private static boolean inEnum(JsonNode shape, String text) {
        for (JsonNode choice : shape.get("enum")) {
            if (choice.asText().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> enumValues(JsonNode shape) {
        List<String> values = new ArrayList<>();
        shape.get("enum").forEach(choice -> values.add(choice.asText()));
        return values;
    }

    /** The rules a string shape declares, a minimum of zero included, in the order AWS lists them. */
    private List<String> rulesOf(String name) {
        JsonNode shape = model.path("shapes").path(name);
        List<String> rules = new ArrayList<>();
        if (shape.has("max")) {
            rules.add("Member must have length less than or equal to " + shape.get("max").asLong());
        }
        if (shape.has("min")) {
            rules.add("Member must have length greater than or equal to " + shape.get("min").asLong());
        }
        if (shape.has("pattern")) {
            rules.add("Member must satisfy regular expression pattern: " + VendoredPatterns.quoted(shape));
        }
        if (shape.has("enum")) {
            rules.add("Member must satisfy enum value set: " + enumValues(shape));
        }
        return rules;
    }

    private List<String> nullableRules(String name) {
        List<String> rules = rulesOf(name);
        rules.add("Member must not be null");
        return rules;
    }

    private static String sizeViolation(JsonNode shape, int size) {
        if (shape.has("min") && size < shape.get("min").asLong()) {
            return "Member must have length greater than or equal to " + shape.get("min").asLong();
        }
        if (shape.has("max") && size > shape.get("max").asLong()) {
            return "Member must have length less than or equal to " + shape.get("max").asLong();
        }
        return null;
    }

    private static String elementsText(JsonNode list) {
        List<String> texts = new ArrayList<>();
        list.forEach(item -> texts.add(elementText(item)));
        return texts.toString();
    }

    private static String elementText(JsonNode item) {
        return item.isNull() ? "null" : item.asText();
    }

    private static SmithyValidationErrors.Violations formOf(JsonNode shape) {
        return shape.path("sensitive").asBoolean(false)
                ? SmithyValidationErrors.Violations.WITHOUT_VALUES
                : SmithyValidationErrors.Violations.QUOTED;
    }

    private static String lowerFirst(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
