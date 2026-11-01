package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks a wire-format AttributeValue the way AWS does before a request runs.
 * Base64 is checked while the request is read, so a bad value is a
 * SerializationException. The one-type rule is a ValidationException.
 */
final class DynamoDbAttributeValueValidator {

    private static final List<String> TYPES = List.of("S", "N", "B", "BOOL", "NULL", "SS", "NS", "BS", "L", "M");

    static final int MAX_NESTING_LEVELS = 32;

    private static final String INVALID = "One or more parameter values were invalid: ";

    // Rust's Debug format also escapes these Other_Grapheme_Extend characters.
    private static final Set<Integer> SPACING_GRAPHEME_EXTENDERS = Set.of(
            0x09BE, 0x09D7, 0x0B3E, 0x0B57, 0x0BBE, 0x0BD7, 0x0CC2, 0x0CD5, 0x0CD6, 0x0D3E, 0x0D57,
            0x0DCF, 0x0DDF, 0x1B35, 0x302E, 0x302F, 0xFF9E, 0xFF9F, 0x1133E, 0x11357, 0x114B0,
            0x114BD, 0x115AF, 0x11930, 0x1D165, 0x1D16E, 0x1D16F, 0x1D170, 0x1D171, 0x1D172);

    private DynamoDbAttributeValueValidator() {}

    // DynamoDB reads the values of a request member in order and refuses the first one it cannot store.
    static void requireStorable(JsonNode values, String prefix) {
        String problem = firstProblem(values);
        if (problem != null) {
            throw validationEx(prefix + problem);
        }
    }

    private static String firstProblem(JsonNode values) {
        for (JsonNode value : values) {
            String problem = problemOf(value);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    // DynamoDB reads a NULL flag written as a string in any case.
    static boolean hasFalseNull(JsonNode value) {
        JsonNode flag = value.get("NULL");
        if (flag == null) {
            return false;
        }
        return flag.isTextual() ? !"true".equalsIgnoreCase(flag.textValue()) : !flag.asBoolean();
    }

    private static String problemOf(JsonNode value) {
        if (hasFalseNull(value)) {
            return INVALID + "Null attribute value types must have the value of true";
        }
        if (value.path("N").isTextual()) {
            String problem = DynamoDbNumberUtils.itemNumberProblem(value.get("N").asText());
            if (problem != null) {
                return problem;
            }
        }
        for (String setType : List.of("NS", "SS", "BS")) {
            if (value.has(setType)) {
                String problem = setProblem(setType, value.get(setType));
                if (problem != null) {
                    return problem;
                }
            }
        }
        String problem = firstProblem(value.path("L"));
        return problem != null ? problem : firstProblem(value.path("M"));
    }

    // A number set is read in full before its members are compared by value.
    private static String setProblem(String setType, JsonNode members) {
        if (members.isEmpty()) {
            return INVALID + switch (setType) {
                case "NS" -> "An number set  may not be empty";
                case "BS" -> "Binary sets should not be empty";
                default -> "An string set  may not be empty";
            };
        }
        if ("NS".equals(setType)) {
            for (JsonNode member : members) {
                String problem = member.isTextual() ? DynamoDbNumberUtils.itemNumberProblem(member.asText()) : null;
                if (problem != null) {
                    return problem;
                }
            }
        }
        List<JsonNode> held = DynamoDbSetOrder.orderAtFirstRepeat(setType, members);
        if (held == null) {
            return null;
        }
        return INVALID + switch (setType) {
            case "NS" -> "Input collection contains duplicates.";
            case "BS" -> "Input collection [" + joined(held, ",", text -> "\"" + text + "\"")
                    + "]of type BS contains duplicates.";
            default -> "Input collection [" + joined(held, ", ", DynamoDbAttributeValueValidator::debugString)
                    + "] contains duplicates.";
        };
    }

    private static String joined(List<JsonNode> members, String separator, Function<String, String> format) {
        return members.stream().map(member -> format.apply(member.asText())).collect(Collectors.joining(separator));
    }

    // DynamoDB quotes a string as Rust's Debug format does.
    private static String debugString(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            switch (c) {
                case 0 -> quoted.append("\\0");
                case '\t' -> quoted.append("\\t");
                case '\r' -> quoted.append("\\r");
                case '\n' -> quoted.append("\\n");
                case '"', '\\' -> quoted.append('\\').appendCodePoint(c);
                default -> {
                    if (printable(c)) {
                        quoted.appendCodePoint(c);
                    } else {
                        quoted.append("\\u{").append(Integer.toHexString(c)).append('}');
                    }
                }
            }
        });
        return quoted.append('"').toString();
    }

    private static boolean printable(int c) {
        return switch (Character.getType(c)) {
            case Character.SPACE_SEPARATOR -> c == ' ';
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL, Character.FORMAT,
                    Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> false;
            default -> !SPACING_GRAPHEME_EXTENDERS.contains(c);
        };
    }

    static void validate(JsonNode value) {
        requireBase64(value);
        requireOneType(value);
    }

    static String typeOf(JsonNode value) {
        if (!value.isObject()) {
            throw serializationEx("Unexpected value type in payload");
        }
        List<String> types = TYPES.stream().filter(value::has).toList();
        if (types.isEmpty()) {
            throw validationEx("Supplied AttributeValue is empty, must contain exactly one of the supported datatypes");
        }
        if (types.size() > 1) {
            throw validationEx("Supplied AttributeValue has more than one datatypes set, "
                    + "must contain exactly one of the supported datatypes");
        }
        return types.getFirst();
    }

    // AWS counts a top-level attribute as level 1 and allows a leaf down to level 32.
    static void requireNestingWithinLimit(JsonNode attributes) {
        requireNestingWithinLimit(attributes, true);
    }

    // Batch and transact writes report the limit without the validation envelope.
    static void requireNestingWithinLimit(JsonNode attributes, boolean inValidationEnvelope) {
        if (!nestingWithinLimit(attributes)) {
            throw validationEx((inValidationEnvelope ? "1 validation error detected: " : "")
                    + "Nesting Levels have exceeded supported limits: "
                    + "Attributes in the item have nested levels beyond supported limit");
        }
    }

    static final String NESTING_EXCEEDED = "Nesting Levels have exceeded supported limits";

    static void requireParameterLevelWithinLimit(int level) {
        if (MAX_NESTING_LEVELS + 1 < level) {
            throw validationEx(NESTING_EXCEEDED + ": Attributes in the item have nested levels beyond supported limit");
        }
    }

    static boolean valueNestingWithinLimit(JsonNode value) {
        return depthOf(value) <= MAX_NESTING_LEVELS;
    }

    static boolean nestingWithinLimit(JsonNode attributes) {
        if (attributes == null || !attributes.isObject()) {
            return true;
        }
        for (JsonNode value : attributes) {
            if (!valueNestingWithinLimit(value)) {
                return false;
            }
        }
        return true;
    }

    private static int depthOf(JsonNode value) {
        if (!value.isObject()) {
            return 1;
        }
        JsonNode children = value.has("M") ? value.get("M") : value.get("L");
        if (children == null) {
            return 1;
        }
        int deepest = 0;
        for (JsonNode child : children) {
            deepest = Math.max(deepest, depthOf(child));
        }
        return 1 + deepest;
    }

    private static void requireOneType(JsonNode value) {
        switch (typeOf(value)) {
            case "L" -> value.get("L").forEach(DynamoDbAttributeValueValidator::requireOneType);
            case "M" -> value.get("M").forEach(DynamoDbAttributeValueValidator::requireOneType);
            default -> { }
        }
    }

    // A PartiQL parameter takes a member set to null as absent.
    static JsonNode dropNullMembers(JsonNode value) {
        if (value instanceof ObjectNode object) {
            object.properties().removeIf(member -> member.getValue().isNull());
            object.path("M").forEach(DynamoDbAttributeValueValidator::dropNullMembers);
            object.path("L").forEach(DynamoDbAttributeValueValidator::dropNullMembers);
        }
        return value;
    }

    static void requireBase64(JsonNode value) {
        if (!value.isObject()) {
            return;
        }
        if (value.has("B")) {
            requireBase64Text(value.get("B"));
        }
        if (value.has("BS")) {
            value.get("BS").forEach(DynamoDbAttributeValueValidator::requireBase64Text);
        }
        if (value.has("L")) {
            value.get("L").forEach(DynamoDbAttributeValueValidator::requireBase64);
        }
        if (value.has("M")) {
            value.get("M").forEach(DynamoDbAttributeValueValidator::requireBase64);
        }
    }

    // AWS checks the length before the alphabet, and names only the length.
    private static void requireBase64Text(JsonNode encoded) {
        if (!encoded.isTextual()) {
            throw serializationEx("only base-64-encoded strings are convertible to bytes");
        }
        String text = encoded.asText();
        if (text.length() % 4 != 0) {
            throw serializationEx("Base64 encoded length is expected a multiple of 4 bytes but found: " + text.length());
        }
        try {
            Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw serializationEx("Unexpected value type in payload");
        }
    }

    private static AwsException validationEx(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException serializationEx(String message) {
        return new AwsException("SerializationException", message, 400);
    }
}
