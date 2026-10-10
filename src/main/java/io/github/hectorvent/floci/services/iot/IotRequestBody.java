package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

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
}
