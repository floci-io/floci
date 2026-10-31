package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * DynamoDB stores a set's members in its own order, not as written: strings by
 * code point, numbers by value, binaries by unsigned byte.
 */
final class DynamoDbSetOrder {

    private record Keyed<K>(JsonNode member, K key) {}

    private DynamoDbSetOrder() {}

    /** Returns the members in DynamoDB's order, or the input itself when it is not an array. */
    static JsonNode sorted(String setType, JsonNode members) {
        if (!members.isArray()) {
            return members;
        }
        return switch (setType) {
            case "NS" -> sortedBy(members, DynamoDbSetOrder::numberOf, Comparator.naturalOrder());
            case "BS" -> sortedBy(members, DynamoDbSetOrder::bytesOf, Arrays::compareUnsigned);
            default -> sortedBy(members, DynamoDbSetOrder::utf8Of, Arrays::compareUnsigned);
        };
    }

    private static <K> ArrayNode sortedBy(JsonNode members, Function<String, K> key, Comparator<K> order) {
        List<Keyed<K>> keyed = new ArrayList<>(members.size());
        for (JsonNode member : members) {
            keyed.add(new Keyed<>(member, key.apply(member.asText())));
        }
        keyed.sort(Comparator.comparing(Keyed::key, order));
        ArrayNode result = JsonNodeFactory.instance.arrayNode(keyed.size());
        for (Keyed<K> entry : keyed) {
            result.add(entry.member());
        }
        return result;
    }

    // UTF-8 byte order is code point order. String.compareTo is not: it orders by
    // UTF-16 unit, which puts a supplementary character before U+E000..U+FFFF.
    private static byte[] utf8Of(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // Number syntax and base64 are checked when a request is read. A member that
    // still does not parse keeps a stable place instead of failing the write.
    private static BigDecimal numberOf(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static byte[] bytesOf(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ignored) {
            return utf8Of(base64);
        }
    }
}
