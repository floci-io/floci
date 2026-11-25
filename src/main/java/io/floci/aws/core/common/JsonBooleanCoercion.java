package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/** The boolean a JSON string names, as AWS's JSON protocol deserializers read one. */
public final class JsonBooleanCoercion {

    private JsonBooleanCoercion() {
    }

    /**
     * The boolean {@code value} names, in any case and untrimmed: {@code true}, {@code yes}, {@code y},
     * {@code t} or {@code 1}, and {@code false}, {@code no}, {@code n}, {@code f} or {@code 0}, as SES v2's
     * and KMS's deserializers read a string (measured on AWS). Any other string is refused with the
     * deserializer's {@code SerializationException}. Callers read the member's other JSON types themselves,
     * each service's deserializer answering them in its own way.
     */
    public static boolean fromString(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "t", "1" -> true;
            case "false", "no", "n", "f", "0" -> false;
            default -> throw new AwsException("SerializationException",
                    "STRING_VALUE can not be converted to an Boolean", 400);
        };
    }

    /**
     * The boolean member {@code node} holds, false when it is absent or null, as KMS's and Kinesis's deserializers
     * read one (measured on AWS): a JSON boolean, or a string as {@link #fromString} reads it. A number, list or
     * object is refused with the deserializer's {@code SerializationException}; the services word a decimal
     * differently, so the caller gives that message.
     */
    public static boolean fromJson(JsonNode node, String decimalMessage) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return fromString(node.textValue());
        }
        String message = node.isIntegralNumber() ? "NUMBER_VALUE can not be converted to a Boolean"
                : node.isNumber() ? decimalMessage
                : node.isArray() ? "Start of list found where not expected"
                : "Start of structure or map found where not expected.";
        throw new AwsException("SerializationException", message, 400);
    }
}
