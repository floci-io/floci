package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the members of a request are read.
 *
 * <p>The control plane's handler and the data plane's handler both turn the JSON of a
 * request into the map the service reads, and the map is the same either way: the
 * members of the request, in the order they came in, with an object a map of its own and
 * a list a list of its own. Reading them is therefore read in one place.
 */
final class PaymentCryptographyMembers {

    private PaymentCryptographyMembers() {
    }

    /**
     * The members of a node, as the service reads them.
     *
     * @param node the object to read
     * @return the members of the object, in the order they came in
     */
    static Map<String, Object> members(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.properties().forEach(entry -> out.put(entry.getKey(), value(entry.getValue())));
        return out;
    }

    /**
     * One member of a node, as the service reads it.
     *
     * <p>A member the request carries as a null is a member the request does not carry,
     * and not a member that carries the text {@code "null"}. A boolean is a boolean, and
     * not its text.
     *
     * @param node the value to read
     * @return the value, as the service reads it
     */
    static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return members(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(child -> values.add(value(child)));
            return values;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
