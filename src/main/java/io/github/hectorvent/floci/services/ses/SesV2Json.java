package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-parsing helpers shared by the SES v2 REST JSON controllers: body and member shape checks
 * that answer with the probe-confirmed {@code BadRequestException} / {@code SerializationException}
 * wordings, the resource {@code Tags} array, and the v1-to-v2 error remapping applied at the v2
 * boundary. Stateless by design so every controller can call them without a bean; the one helper
 * that reads a body takes the caller's {@link ObjectMapper}.
 */
final class SesV2Json {

    private SesV2Json() {
    }

    static AwsException remapV1Exception(AwsException e) {
        return switch (e.getErrorCode()) {
            case "InvalidParameterValue", "InvalidTemplate", "ValidationError",
                 "InvalidRenderingParameter", "MissingRenderingAttribute" ->
                    new AwsException("BadRequestException", e.getMessage(), 400);
            case "TemplateDoesNotExist", "ConfigurationSetDoesNotExist",
                 "CustomVerificationEmailTemplateDoesNotExist", "FromEmailAddressNotVerified" ->
                    new AwsException("NotFoundException", e.getMessage(), 404);
            case "AlreadyExists", "ConfigurationSetAlreadyExists",
                 "CustomVerificationEmailTemplateAlreadyExists" ->
                    new AwsException("AlreadyExistsException", e.getMessage(), 400);
            case "ConfigurationSetSendingPausedException" ->
                    new AwsException("SendingPausedException", e.getMessage(), 400);
            default -> e;
        };
    }

    static void requireJsonObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new AwsException("BadRequestException",
                    "Request body must be a JSON object.", 400);
        }
    }

    static JsonNode requireObjectOrAbsent(JsonNode parent, String fieldName) {
        JsonNode child = parent.path(fieldName);
        if (!child.isMissingNode() && !child.isNull() && !child.isObject()) {
            throw new AwsException("BadRequestException",
                    fieldName + " must be a JSON object.", 400);
        }
        return child;
    }

    /**
     * Parse a JSON {@code Tags} array node into a list of tag records. Returns {@code null}
     * when the node is missing or null so callers can decide whether that is an error
     * (TagResource) or a no-op (CreateConfigurationSet / CreateEmailTemplate). Throws
     * {@code BadRequestException} when the node is present but not an array.
     */
    static List<Tag> parseTagsArray(JsonNode tagsNode) {
        if (tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("BadRequestException", "Tags must be an array.", 400);
        }
        List<Tag> out = new ArrayList<>();
        for (JsonNode t : tagsNode) {
            // Each element must be a JSON object. A scalar/array/null element is a wire deserialization
            // error (AWS returns SerializationException for a scalar/array element; it returns a 500
            // InternalFailure for a null element, a server-side bug we normalize to the same 400).
            if (!t.isObject()) {
                throw new AwsException("SerializationException", null, 400);
            }
            JsonNode key = t.path("Key");
            JsonNode value = t.path("Value");
            // A present-but-non-string Key/Value (number, boolean, object, array) is a wire
            // deserialization error, not a coercible value: AWS restJson1 rejects it with
            // SerializationException rather than turning 123 into "123". A missing/null member is left
            // to the downstream service validation, matching AWS.
            if (nonStringMember(key) || nonStringMember(value)) {
                throw new AwsException("SerializationException", null, 400);
            }
            out.add(new Tag(key.asText(null), value.asText(null)));
        }
        return out;
    }

    private static boolean nonStringMember(JsonNode node) {
        return !node.isMissingNode() && !node.isNull() && !node.isTextual();
    }

    /** Parse an option PUT body into a JSON object, treating an empty body as {}. */
    static JsonNode readOptionBody(ObjectMapper objectMapper, String body) {
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            return request;
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    /** Read an optional string member, rejecting a non-string value the way the AWS deserialization layer does. */
    static String parseOptionString(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("BadRequestException", field + " must be a JSON string.", 400);
        }
        return node.asText();
    }

    // AWS-verified Jackson coercion for a SES v2 boolean field: a JSON string coerces to true,
    // while a number/null/array/object is a SerializationException.
    static boolean coerceBoolean(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return true;
        }
        if (node.isNull()) {
            throw new AwsException("SerializationException", null, 400);
        }
        if (node.isNumber()) {
            throw new AwsException("SerializationException",
                    "NUMBER_VALUE can not be converted to a Boolean", 400);
        }
        throw unexpectedStartError(node);
    }

    static AwsException unexpectedStartError(JsonNode node) {
        if (node.isArray()) {
            return new AwsException("SerializationException",
                    "Start of list found where not expected", 400);
        }
        return new AwsException("SerializationException",
                "Start of structure or map found where not expected.", 400);
    }

    // Read a typed string member: absent/null returns null, but a present value of the wrong JSON type
    // is rejected rather than coerced (asText would turn 123 into "123"), matching AWS.
    static String stringMemberOrAbsent(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new AwsException("SerializationException", null, 400);
        }
        return n.textValue();
    }

    // Parse an optional array of strings: absent/null returns null, an empty array stays an empty
    // list (the distinction matters for the suppression-attributes pair rules), and a non-string
    // element is rejected rather than coerced.
    static List<String> stringArrayOrAbsent(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw new AwsException("SerializationException", null, 400);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) {
                throw new AwsException("SerializationException", null, 400);
            }
            values.add(item.textValue());
        }
        return values;
    }

    // Integer variant of stringMemberOrAbsent: absent/null returns null, non-integral JSON is
    // rejected rather than coerced, and so is an integral value outside the int range (intValue
    // would silently truncate it).
    static Integer intMemberOrAbsent(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new AwsException("SerializationException", null, 400);
        }
        return n.intValue();
    }

    static String readRequiredStringField(JsonNode request, String fieldName) {
        JsonNode node = request.path(fieldName);
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            throw new AwsException("BadRequestException", fieldName + " is required.", 400);
        }
        return node.asText();
    }
}
