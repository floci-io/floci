package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Request parsing and error helpers shared by the Redshift Serverless administration handlers and
 * services. A member that is missing or null reads as {@code null}; a member of the wrong type is a
 * {@code ValidationException}, so a malformed request is never mistaken for an omitted field.
 */
final class ServerlessSupport {

    /** Use only for members whose model overrides the epoch-seconds default with ISO 8601. */
    static final DateTimeFormatter ISO_8601 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ServerlessSupport() {
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
    }

    static String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return node.textValue();
    }

    static Integer integer(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw validation(field + " must be an integer.");
        }
        return node.asInt();
    }

    static Boolean bool(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return node.asBoolean();
    }

    /** A timestamp member, which awsJson1.1 sends as epoch seconds unless the model says otherwise. */
    static Instant epochSeconds(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw validation(field + " must be a timestamp.");
        }
        return Instant.ofEpochMilli(Math.round(node.asDouble() * 1000));
    }

    static List<String> stringList(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw validation(field + " must contain only strings.");
            }
            list.add(element.textValue());
        }
        return list;
    }

    static JsonNode object(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw validation(field + " must be an object.");
        }
        return node;
    }

    static Integer maxResults(JsonNode request) {
        return integer(request, "maxResults");
    }
}
