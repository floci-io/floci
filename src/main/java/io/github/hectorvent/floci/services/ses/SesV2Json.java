package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.ses.model.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Helpers shared by the SES v2 REST JSON controllers: body and member shape checks that answer
 * with the probe-confirmed {@code BadRequestException} / {@code SerializationException} wordings,
 * the resource {@code Tags} array, the v1-to-v2 error remapping applied at the v2 boundary, and the
 * epoch-seconds timestamp members of a response. Stateless by design so every controller can call
 * them without a bean; the one helper that reads a body takes the caller's {@link ObjectMapper}.
 */
final class SesV2Json {

    /** The deserializer's wording, which {@link SesV2RequestShapes}' walk of the body shapes shares. */
    static final String SERIALIZATION_EXCEPTION = "SerializationException";
    static final String CONVERSION = "%s can not be converted to a %s";
    static final String INTEGER_CONVERSION = "%s can not be converted to an Integer";
    static final String EXPECTED_NULL = "Expected null";
    static final String EXPECTED_MAP = "Expected map or null";
    static final String EXPECTED_LIST = "Expected list or null";
    static final String LIST_NOT_EXPECTED = "Start of list found where not expected";
    static final String OBJECT_NOT_EXPECTED = "Start of structure or map found where not expected.";

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

    /** A request body that is not a JSON object: AWS names a list and answers anything else without a message. */
    static void requireJsonObject(JsonNode root) {
        if (root != null && root.isObject()) {
            return;
        }
        if (root != null && root.isArray()) {
            throw unexpectedStartError(root);
        }
        throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
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
                throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
            }
            JsonNode key = t.path("Key");
            JsonNode value = t.path("Value");
            // A present-but-non-string Key/Value (number, boolean, object, array) is a wire
            // deserialization error, not a coercible value: AWS restJson1 rejects it with
            // SerializationException rather than turning 123 into "123". A missing/null member is left
            // to the downstream service validation, matching AWS.
            if (nonStringMember(key) || nonStringMember(value)) {
                throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
            }
            out.add(new Tag(key.asText(null), value.asText(null)));
        }
        return out;
    }

    private static boolean nonStringMember(JsonNode node) {
        return !node.isMissingNode() && !node.isNull() && !node.isTextual();
    }

    /**
     * Parse a request body into a JSON object. Only an empty body reads as {}: AWS answers a
     * whitespace-only body, malformed JSON and a trailing token after the object (even a second
     * value) with a {@code SerializationException} without a message.
     */
    static JsonNode readOptionBody(ObjectMapper objectMapper, String body) {
        if (body == null || body.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        JsonNode request;
        try {
            request = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException e) {
            throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
        }
        requireJsonObject(request);
        return request;
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

    // AWS-verified coercion for a SES v2 boolean field: a JSON string coerces only when it names a
    // boolean (case-insensitive, untrimmed); any other string, a number, null, an array or an object
    // is a SerializationException.
    static boolean coerceBoolean(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return switch (node.textValue().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "y", "t", "1" -> true;
                case "false", "no", "n", "f", "0" -> false;
                default -> throw new AwsException(SERIALIZATION_EXCEPTION,
                        "STRING_VALUE can not be converted to an Boolean", 400);
            };
        }
        if (node.isNull()) {
            throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
        }
        throw scalarTypeError(node, "Boolean");
    }

    /** A string member with the AWS deserializer's message for each wrong JSON type. */
    private static String typedStringMemberOrAbsent(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        throw scalarTypeError(node, "String");
    }

    /**
     * A string-to-string map member, such as a list operation's {@code Filter}: absent or null is
     * null, a null value is kept as null so a key check still sees its key, and a value of another
     * JSON type gets the deserializer's message for it.
     */
    static Map<String, String> stringMapMemberOrAbsent(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            throw unexpectedStartError(node);
        }
        if (!node.isObject()) {
            throw expectedMapError();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            values.put(entry.getKey(), typedStringMemberOrAbsent(node, entry.getKey()));
        }
        return values;
    }

    /**
     * A {@code Filter} map checked against the model's own constraints, as the SES validation layer
     * reports them: every key in the enum, listed in the order SES prints it, and no empty value, a
     * key being checked before a value. Returns the entries that carry a value; an absent filter is
     * an empty one.
     */
    static Map<String, String> filterValues(Map<String, String> filter, List<String> keys) {
        return filterValues(filter, keys, String::isEmpty, "Member must have length greater than or equal to 1");
    }

    /**
     * As above, for a filter whose values are bounded above too. SES printed both bounds for an
     * empty value, so a value over the bound is taken to be answered the same way.
     */
    static Map<String, String> filterValues(Map<String, String> filter, List<String> keys, int maxValueLength) {
        return filterValues(filter, keys,
                v -> v.isEmpty() || v.codePointCount(0, v.length()) > maxValueLength,
                "Member must have length less than or equal to " + maxValueLength
                        + ", Member must have length greater than or equal to 1");
    }

    private static Map<String, String> filterValues(Map<String, String> filter, List<String> keys,
                                                    Predicate<String> invalidValue, String valueConstraint) {
        if (filter == null) {
            return Map.of();
        }
        if (!keys.containsAll(filter.keySet())) {
            throw SmithyValidationErrors.badRequest( "Value at 'filter' "
                    + "failed to satisfy constraint: Map keys must satisfy constraint: "
                    + "[Member must satisfy enum value set: [" + String.join(", ", keys) + "]]");
        }
        if (filter.values().stream().anyMatch(v -> v != null && invalidValue.test(v))) {
            throw SmithyValidationErrors.badRequest( "Value at 'filter' "
                    + "failed to satisfy constraint: Map value must satisfy constraint: "
                    + "[" + valueConstraint + "]");
        }
        Map<String, String> values = new LinkedHashMap<>();
        filter.forEach((key, value) -> {
            if (value != null) {
                values.put(key, value);
            }
        });
        return values;
    }

    /**
     * The deserializer's message for a scalar member of type {@code target} (such as
     * {@code "String"}, {@code "Blob"} or {@code "Boolean"}) given a value of another JSON type.
     */
    static AwsException scalarTypeError(JsonNode node, String target) {
        if (node.isNumber()) {
            return new AwsException(SERIALIZATION_EXCEPTION, CONVERSION.formatted("NUMBER_VALUE", target), 400);
        }
        if (node.isBoolean()) {
            return new AwsException(SERIALIZATION_EXCEPTION,
                    CONVERSION.formatted(node.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE", target), 400);
        }
        return unexpectedStartError(node);
    }

    /** The deserializer's message for a structure member given a scalar. */
    static AwsException expectedNullError() {
        return new AwsException(SERIALIZATION_EXCEPTION, EXPECTED_NULL, 400);
    }

    /** The deserializer's message for a map member given a scalar. */
    static AwsException expectedMapError() {
        return new AwsException(SERIALIZATION_EXCEPTION, EXPECTED_MAP, 400);
    }

    /** The deserializer's message for a list member given a scalar. */
    static AwsException expectedListError() {
        return new AwsException(SERIALIZATION_EXCEPTION, EXPECTED_LIST, 400);
    }

    static AwsException unexpectedStartError(JsonNode node) {
        if (node.isArray()) {
            return new AwsException(SERIALIZATION_EXCEPTION, LIST_NOT_EXPECTED, 400);
        }
        return new AwsException(SERIALIZATION_EXCEPTION, OBJECT_NOT_EXPECTED, 400);
    }

    // Read a typed string member: absent/null returns null, but a present value of the wrong JSON type
    // is rejected rather than coerced (asText would turn 123 into "123"), matching AWS.
    static String stringMemberOrAbsent(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
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
            throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) {
                throw new AwsException(SERIALIZATION_EXCEPTION, null, 400);
            }
            values.add(item.textValue());
        }
        return values;
    }

    // Integer variant of stringMemberOrAbsent: absent/null returns null and a string, a boolean, an
    // object or a list gets the deserializer's message for it. A number is narrowed to an int: AWS
    // accepts 1.5 and range-checks 4294967296 as 2147483647, which a double-to-int cast reproduces
    // where intValue would truncate 4294967296 to 0.
    static Integer intMemberOrAbsent(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            throw new AwsException(SERIALIZATION_EXCEPTION, INTEGER_CONVERSION.formatted("STRING_VALUE"), 400);
        }
        if (n.isBoolean()) {
            throw new AwsException(SERIALIZATION_EXCEPTION,
                    INTEGER_CONVERSION.formatted(n.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE"), 400);
        }
        if (n.isContainerNode()) {
            throw unexpectedStartError(n);
        }
        return (int) n.doubleValue();
    }

    static String readRequiredStringField(JsonNode request, String fieldName) {
        JsonNode node = request.path(fieldName);
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            throw new AwsException("BadRequestException", fieldName + " is required.", 400);
        }
        return node.asText();
    }

    /**
     * An optional SES v2 boolean member whose absence means {@code false}: a missing member is
     * {@code false}, and a present value, {@code null} included, goes through
     * {@link #coerceBoolean(JsonNode)}. AWS-verified for the account's and a configuration set's
     * SendingEnabled, SigningEnabled, EmailForwardingEnabled, ReputationMetricsEnabled and an event
     * destination's Enabled.
     */
    static boolean coerceBooleanOrFalse(JsonNode enabledNode) {
        if (enabledNode.isMissingNode()) {
            return false;
        }
        return coerceBoolean(enabledNode);
    }

    /**
     * Epoch seconds with the millisecond fraction, the shape AWS returns for every SES v2
     * timestamp (probe-confirmed, e.g. {@code 1.790312384592E9}). It must stay a JSON number: an
     * ISO string, as the v1 Query path writes, breaks the SDK's unixTimestamp unmarshaller.
     */
    static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    static void putTimestamp(ObjectNode node, String field, Instant value) {
        if (value != null) {
            node.put(field, epochSeconds(value));
        }
    }
}
