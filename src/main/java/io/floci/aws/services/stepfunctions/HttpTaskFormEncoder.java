package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.auth.SigV4Canonicalization;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** Object-body and scalar-array encodings documented for Step Functions HTTP Tasks. */
final class HttpTaskFormEncoder {

    private enum ArrayFormat { INDICES, REPEAT, COMMAS, BRACKETS }

    private HttpTaskFormEncoder() {
    }

    // https://docs.aws.amazon.com/step-functions/latest/dg/call-https-apis.html#connect-http-task-url-encoding
    static String encode(JsonNode body, JsonNode options) {
        if (!options.isMissingNode() && !options.isObject()) {
            throw new IllegalArgumentException("RequestEncodingOptions must be a JSON object");
        }
        JsonNode configuredFormat = options.path("ArrayFormat");
        ArrayFormat format = ArrayFormat.INDICES;
        if (!configuredFormat.isMissingNode()) {
            if (!configuredFormat.isTextual()) {
                throw new IllegalArgumentException("ArrayFormat must be a supported string value");
            }
            try {
                format = ArrayFormat.valueOf(configuredFormat.textValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unsupported ArrayFormat: " + configuredFormat.textValue());
            }
        }
        if (body.isMissingNode() || body.isNull()) {
            return "";
        }
        if (!body.isObject()) {
            throw new IllegalArgumentException("Floci URL_ENCODED request bodies currently require a JSON object");
        }
        List<String> pairs = new ArrayList<>();
        ArrayFormat selected = format;
        body.fields().forEachRemaining(entry -> append(pairs, entry.getKey(), entry.getValue(), selected));
        return String.join("&", pairs);
    }

    private static void append(List<String> pairs, String key, JsonNode value, ArrayFormat format) {
        if (value.isObject()) {
            value.fields().forEachRemaining(entry ->
                    append(pairs, key + "[" + entry.getKey() + "]", entry.getValue(), format));
        } else if (value.isArray()) {
            if (value.isEmpty()) {
                return;
            }
            if (format == ArrayFormat.COMMAS) {
                StringJoiner values = new StringJoiner(",");
                for (JsonNode element : value) {
                    if (element.isContainerNode()) {
                        throw new IllegalArgumentException("Floci COMMAS arrays currently require scalar values");
                    }
                    values.add(SigV4Canonicalization.uriEncode(element.isNull() ? "" : element.asText()));
                }
                pairs.add(SigV4Canonicalization.uriEncode(key) + "=" + values);
                return;
            }
            for (int index = 0; index < value.size(); index++) {
                String arrayKey = switch (format) {
                    case INDICES -> key + "[" + index + "]";
                    case BRACKETS -> key + "[]";
                    default -> key;
                };
                append(pairs, arrayKey, value.get(index), format);
            }
        } else {
            pairs.add(SigV4Canonicalization.uriEncode(key) + "="
                    + SigV4Canonicalization.uriEncode(value.isNull() ? "" : value.asText()));
        }
    }
}
