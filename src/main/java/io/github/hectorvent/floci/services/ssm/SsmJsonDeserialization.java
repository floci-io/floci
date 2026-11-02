package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonNumberCoercion;

final class SsmJsonDeserialization {
    private SsmJsonDeserialization() {
    }

    static int optionalInteger(JsonNode node, int defaultValue) {
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (!node.isNumber() || Double.isNaN(node.doubleValue())) {
            throw serializationException(node, "an Integer");
        }
        return JsonNumberCoercion.truncateAndClampToInt(node);
    }

    static AwsException serializationException(JsonNode node, String targetDescription) {
        String token = switch (node.getNodeType()) {
            case STRING -> "STRING_VALUE";
            case NUMBER -> "NUMBER_VALUE";
            case BOOLEAN -> node.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
            default -> node.getNodeType().name();
        };
        String message = switch (node.getNodeType()) {
            case OBJECT -> "Start of structure or map found where not expected.";
            case ARRAY -> "Start of list found where not expected";
            default -> token + " can not be converted to " + targetDescription;
        };
        return new AwsException("SerializationException", message, 400);
    }
}
