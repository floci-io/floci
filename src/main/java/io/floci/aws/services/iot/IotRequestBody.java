package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import io.floci.aws.core.common.AwsException;

import java.util.Locale;

/**
 * Reads an AWS IoT REST-JSON request body. AWS rejects a malformed body, and content after the
 * JSON value, with {@code SerializationException}.
 */
final class IotRequestBody {

    private IotRequestBody() {
    }

    static JsonNode read(ObjectMapper objectMapper, String body) {
        try {
            return objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", e.getOriginalMessage(), 400);
        }
    }

    static void requireObject(JsonNode request) {
        if (!request.isObject()) {
            throw serializationError("request body", JsonNodeType.OBJECT);
        }
    }

    /** A string member, null when it is absent or null. */
    static String text(JsonNode node, String path) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw serializationError(path, JsonNodeType.STRING);
        }
        return node.textValue();
    }

    /** A member of the wrong JSON type fails before any validation, as AWS fails to deserialize it. */
    static AwsException serializationError(String path, JsonNodeType expected) {
        return new AwsException("SerializationException",
                path + " must be a JSON " + expected.name().toLowerCase(Locale.ROOT), 400);
    }
}
