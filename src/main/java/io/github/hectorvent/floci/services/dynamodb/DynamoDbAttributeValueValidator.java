package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Base64;
import java.util.List;

/**
 * Checks a wire-format AttributeValue the way AWS does before a request runs.
 * Base64 is checked while the request is read, so a bad value is a
 * SerializationException. The one-type rule is a ValidationException.
 */
final class DynamoDbAttributeValueValidator {

    private static final List<String> TYPES = List.of("S", "N", "B", "BOOL", "NULL", "SS", "NS", "BS", "L", "M");

    private DynamoDbAttributeValueValidator() {}

    static void validate(JsonNode value) {
        requireBase64(value);
        requireOneType(value);
    }

    static String typeOf(JsonNode value) {
        if (!value.isObject()) {
            throw serializationEx("Unexpected value type in payload");
        }
        var types = TYPES.stream().filter(value::has).toList();
        if (types.isEmpty()) {
            throw validationEx("Supplied AttributeValue is empty, must contain exactly one of the supported datatypes");
        }
        if (types.size() > 1) {
            throw validationEx("Supplied AttributeValue has more than one datatypes set, "
                    + "must contain exactly one of the supported datatypes");
        }
        return types.getFirst();
    }

    private static void requireOneType(JsonNode value) {
        switch (typeOf(value)) {
            case "L" -> value.get("L").forEach(DynamoDbAttributeValueValidator::requireOneType);
            case "M" -> value.get("M").forEach(DynamoDbAttributeValueValidator::requireOneType);
            default -> { }
        }
    }

    private static void requireBase64(JsonNode value) {
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
        var text = encoded.asText();
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
