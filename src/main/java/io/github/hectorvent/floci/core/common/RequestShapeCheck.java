package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks a request value against a {@link RequestShapeModel} as AWS's JSON deserializers read a body,
 * before an operation validates anything, and refuses the first member of the wrong JSON type. The
 * walk is the same for every service: a JSON null is an absent member (a null list element is left
 * to the operation), a member the model does not know is skipped, a structure's values are read as
 * members and a list's or a map's as elements, and a boolean also reads from "true" or "false" in
 * any case. What a service's deserializer says at each point is its {@link Messages}. They also say
 * how it names a scalar of the wrong kind, by its JSON token ({@code NUMBER_VALUE},
 * {@code TRUE_VALUE}, ...) unless the service gives it another name, and whether it reads a
 * structure's members and a map's entries in body order or in name order, which decides the value
 * reported when several are wrong. A list is always read in its own order.
 */
public final class RequestShapeCheck {

    /** A message that depends on whether the value was read as a member or as an element. */
    public record ByPosition(String member, String element) {

        public static ByPosition same(String message) {
            return new ByPosition(message, message);
        }

        String at(boolean asMember) {
            return asMember ? member : element;
        }
    }

    /**
     * One deserializer's wording, as measured on its service. A null message is answered with the
     * error code alone.
     *
     * @param errorCode          the exception every mismatch answers with, as HTTP 400
     * @param objectNotExpected  an object where a list or a scalar is expected
     * @param listNotExpected    a list where a structure, a map or a scalar is expected
     * @param scalarForStructure a scalar where a structure is expected
     * @param scalarForList      a scalar where a list is expected
     * @param scalarForMap       a scalar where a map is expected
     * @param conversion         a scalar of the wrong kind, formatted with its name (its token, or its
     *                           {@code scalarNames} entry) and the target type ({@code String},
     *                           {@code Integer}, {@code Long}, {@code Double}, {@code Date},
     *                           {@code Boolean}, {@code Blob})
     * @param decimalToken       whether a non-integral number is {@code DECIMAL_VALUE} rather than
     *                           {@code NUMBER_VALUE}
     * @param invalidBoolean     text other than true or false where a boolean is expected
     * @param blobNotText        a scalar that is not text where a blob is expected, or null to word
     *                           it as {@code conversion} does
     * @param scalarTypes        the scalar shape types the service was measured on; a value of any
     *                           other scalar type passes unchecked
     * @param scalarNames        how a scalar of the wrong kind is named in a conversion message, keyed
     *                           by its JSON token ({@code NUMBER_VALUE}, {@code DECIMAL_VALUE},
     *                           {@code TRUE_VALUE}, {@code FALSE_VALUE}, {@code STRING_VALUE}); a
     *                           token the map leaves out is named by itself
     * @param conversionByType   a conversion wording per scalar shape type, such as {@code double},
     *                           used in place of {@code conversion} for that type and formatted the
     *                           same way, with the name and then the target; a wording may leave the
     *                           target out
     * @param sortedMembers      whether a structure's members and a map's entries are read in name
     *                           order (Java {@code String} order) rather than body order, so that the
     *                           first bad value by name is reported
     */
    public record Messages(String errorCode, String objectNotExpected, String listNotExpected,
                           ByPosition scalarForStructure, ByPosition scalarForList, ByPosition scalarForMap,
                           String conversion, boolean decimalToken, String invalidBoolean, String blobNotText,
                           Set<String> scalarTypes, Map<String, String> scalarNames,
                           Map<String, String> conversionByType, boolean sortedMembers) {

        public Messages(String errorCode, String objectNotExpected, String listNotExpected,
                        ByPosition scalarForStructure, ByPosition scalarForList, ByPosition scalarForMap,
                        String conversion, boolean decimalToken, String invalidBoolean, String blobNotText,
                        Set<String> scalarTypes) {
            this(errorCode, objectNotExpected, listNotExpected, scalarForStructure, scalarForList, scalarForMap,
                    conversion, decimalToken, invalidBoolean, blobNotText, scalarTypes, Map.of(), Map.of(), false);
        }
    }

    private static final Map<String, String> TARGETS = Map.of("string", "String", "integer", "Integer",
            "long", "Long", "double", "Double", "timestamp", "Date", "boolean", "Boolean", "blob", "Blob");

    private RequestShapeCheck() {
    }

    /** Checks {@code value}, read as a member, against the shape {@code shapeName} of {@code model}. */
    public static void check(RequestShapeModel model, String shapeName, JsonNode value, Messages messages) {
        checkValue(model, model.shapes().get(shapeName), value, true, messages);
    }

    private static void checkValue(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                   Messages messages) {
        if (value == null || value.isNull()) {
            return;
        }
        switch (shape.type()) {
            case "structure" -> checkStructure(model, shape, value, member, messages);
            case "list" -> checkList(model, shape, value, member, messages);
            case "map" -> checkMap(model, shape, value, member, messages);
            default -> checkScalar(shape.type(), value, messages);
        }
    }

    private static void checkStructure(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                       Messages messages) {
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        if (!value.isObject()) {
            throw refusal(messages, messages.scalarForStructure().at(member));
        }
        for (Map.Entry<String, JsonNode> field : fields(value, messages)) {
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null) {
                checkValue(model, model.shapes().get(memberShape), field.getValue(), true, messages);
            }
        }
    }

    /** An object's fields, in body order or, with {@code sortedMembers}, in name order. */
    private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode value, Messages messages) {
        if (!messages.sortedMembers()) {
            return value.properties();
        }
        Map<String, JsonNode> byName = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            byName.put(field.getKey(), field.getValue());
        }
        return byName.entrySet();
    }

    private static void checkList(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                  Messages messages) {
        if (value.isObject()) {
            throw refusal(messages, messages.objectNotExpected());
        }
        if (!value.isArray()) {
            throw refusal(messages, messages.scalarForList().at(member));
        }
        checkElements(model, shape, value, messages);
    }

    private static void checkMap(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                 Messages messages) {
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        if (!value.isObject()) {
            throw refusal(messages, messages.scalarForMap().at(member));
        }
        Shape element = model.shapes().get(shape.element());
        for (Map.Entry<String, JsonNode> entry : fields(value, messages)) {
            checkValue(model, element, entry.getValue(), false, messages);
        }
    }

    private static void checkElements(RequestShapeModel model, Shape shape, JsonNode value, Messages messages) {
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item, false, messages);
        }
    }

    private static void checkScalar(String type, JsonNode value, Messages messages) {
        if (!messages.scalarTypes().contains(type)) {
            return;
        }
        if (value.isObject()) {
            throw refusal(messages, messages.objectNotExpected());
        }
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        switch (type) {
            case "string" -> requireToken(value.isTextual(), value, type, messages);
            case "integer", "long", "double", "timestamp" -> requireToken(value.isNumber(), value, type, messages);
            case "boolean" -> checkBoolean(value, messages);
            case "blob" -> checkBlob(value, messages);
            default -> {
            }
        }
    }

    private static void checkBoolean(JsonNode value, Messages messages) {
        if (value.isTextual()) {
            String text = value.textValue();
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                throw refusal(messages, messages.invalidBoolean());
            }
            return;
        }
        requireToken(value.isBoolean(), value, "boolean", messages);
    }

    private static void checkBlob(JsonNode value, Messages messages) {
        if (value.isTextual() || value.isBinary()) {
            return;
        }
        if (messages.blobNotText() != null) {
            throw refusal(messages, messages.blobNotText());
        }
        requireToken(false, value, "blob", messages);
    }

    private static void requireToken(boolean accepted, JsonNode value, String type, Messages messages) {
        if (!accepted) {
            String token = token(value, messages);
            String format = messages.conversionByType().getOrDefault(type, messages.conversion());
            throw refusal(messages, format.formatted(messages.scalarNames().getOrDefault(token, token),
                    TARGETS.get(type)));
        }
    }

    private static String token(JsonNode value, Messages messages) {
        if (value.isNumber()) {
            return messages.decimalToken() && !value.isIntegralNumber() ? "DECIMAL_VALUE" : "NUMBER_VALUE";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
        }
        return "STRING_VALUE";
    }

    private static AwsException refusal(Messages messages, String message) {
        return new AwsException(messages.errorCode(), message, 400);
    }
}
