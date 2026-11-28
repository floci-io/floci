package io.floci.aws.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded JSON Schema traversal for API Gateway's sample mapping templates. */
public final class ModelTemplateGenerator {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_SCHEMA_CHARACTERS = 1_048_576;
    private static final int MAX_NODES = 4_096;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_OUTPUT_CHARACTERS = 262_144;
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");

    private ModelTemplateGenerator() {}

    public static String generate(String schema, String contentType) {
        if (contentType != null && !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw unsupported("Only application/json model templates are supported.");
        }
        if (schema == null || schema.isBlank()) {
            throw new AwsException("BadRequestException", "The model does not contain a JSON schema.", 400);
        }
        if (schema.length() > MAX_SCHEMA_CHARACTERS) {
            throw unsupported("The model schema exceeds the local template generation limit.");
        }
        JsonNode document;
        try {
            document = JSON.readTree(schema);
        } catch (JsonProcessingException error) {
            throw new AwsException("BadRequestException", "The model schema is not valid JSON.", 400);
        }
        Compiler compiler = new Compiler(document);
        Shape shape = compiler.read(document, 0, new HashSet<>());
        if (!"object".equals(shape.type()) && !"array".equals(shape.type())) {
            throw new AwsException("BadRequestException",
                    "Unsupported data type for mapping template: " + shape.type(), 400);
        }
        Output output = new Output();
        output.append("#set($inputRoot = $input.path('$'))\n");
        render(shape, 0, output);
        return output.text.toString();
    }

    private static void render(Shape shape, int depth, Output output) {
        String indent = "  ".repeat(depth);
        String childIndent = indent + "  ";
        switch (shape.type()) {
            case "object" -> {
                output.append("{");
                boolean first = true;
                for (Map.Entry<String, Shape> property : shape.properties().entrySet()) {
                    if (!first) {
                        output.append(",");
                    }
                    first = false;
                    output.append("\n" + childIndent + TextNode.valueOf(property.getKey()) + " : ");
                    render(property.getValue(), depth + 1, output);
                }
                if (!first) {
                    output.append("\n" + indent);
                }
                output.append("}");
            }
            case "array" -> {
                output.append("[\n" + indent
                        + "##TODO: Update this foreach loop to reference array from input json\n"
                        + indent + "#foreach($elem in $inputRoot.TODO)\n" + indent + " ");
                render(shape.items(), depth + 1, output);
                output.append(" \n" + indent + "#if($foreach.hasNext),#end\n"
                        + indent + "#end\n" + indent + "]");
            }
            case "string" -> output.append("\"foo\"");
            case "integer" -> output.append("1");
            case "number" -> output.append("3.1415");
            case "boolean" -> output.append("true");
            case "null" -> output.append("null");
            default -> throw new IllegalStateException("Uncompiled schema type");
        }
    }

    private static AwsException unsupported(String message) {
        return new AwsException("UnsupportedOperation", message, 501);
    }

    private record Shape(String type, Map<String, Shape> properties, Shape items) {}

    private static final class Compiler {
        private final JsonNode root;
        private int nodes;

        private Compiler(JsonNode root) {
            this.root = root;
        }

        private Shape read(JsonNode schema, int depth, Set<String> references) {
            nodes++;
            if (nodes > MAX_NODES || depth > MAX_DEPTH) {
                throw unsupported("The model schema exceeds the local template traversal limit.");
            }
            if (schema == null || !schema.isObject()) {
                throw unsupported("Model templates require JSON Schema objects.");
            }
            for (String keyword : Set.of("allOf", "anyOf", "oneOf", "not", "enum", "const")) {
                if (schema.has(keyword)) {
                    throw unsupported("Model template generation does not support " + keyword + ".");
                }
            }
            if (schema.has("$ref")) {
                String reference = schema.path("$ref").asText();
                if (!reference.startsWith("#/")) {
                    throw unsupported("Only finite local JSON Pointer references are supported.");
                }
                if (!references.add(reference)) {
                    throw unsupported("Cyclic model references are not supported.");
                }
                try {
                    JsonNode target = root.at(reference.substring(1));
                    if (target.isMissingNode()) {
                        throw unsupported("The local model reference could not be resolved.");
                    }
                    return read(target, depth + 1, references);
                } catch (IllegalArgumentException error) {
                    throw unsupported("The local model reference is not a valid JSON Pointer.");
                } finally {
                    references.remove(reference);
                }
            }
            JsonNode declaredType = schema.get("type");
            if (declaredType != null && !declaredType.isTextual()) {
                throw unsupported("Model templates require a single supported JSON type at each value.");
            }
            String type = declaredType == null ? "" : declaredType.textValue();
            if (declaredType == null && schema.has("properties")) {
                type = "object";
            } else if (declaredType == null && schema.has("items")) {
                type = "array";
            }
            if (!TYPES.contains(type)) {
                throw unsupported("Model templates require a single supported JSON type at each value.");
            }
            Map<String, Shape> properties = new LinkedHashMap<>();
            Shape items = null;
            if ("object".equals(type) && schema.has("properties")) {
                JsonNode declared = schema.get("properties");
                if (!declared.isObject()) {
                    throw unsupported("Model properties must be a JSON Schema object.");
                }
                for (Map.Entry<String, JsonNode> property : declared.properties()) {
                    String name = property.getKey();
                    if (name.chars().anyMatch(character -> character < 32 || "'\"\\$#".indexOf(character) >= 0)) {
                        throw unsupported("Property names containing VTL delimiters or control characters are not supported.");
                    }
                    properties.put(name, read(property.getValue(), depth + 1, references));
                }
            } else if ("array".equals(type)) {
                items = read(schema.get("items"), depth + 1, references);
            }
            return new Shape(type, properties, items);
        }
    }

    private static final class Output {
        private final StringBuilder text = new StringBuilder();

        private void append(String value) {
            if (text.length() + value.length() > MAX_OUTPUT_CHARACTERS) {
                throw unsupported("The generated template exceeds the local output limit.");
            }
            text.append(value);
        }
    }
}
