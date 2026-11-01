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
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * DynamoDB stores a set's members in its own order, not as written: strings by
 * code point, numbers by value, binaries by unsigned byte.
 */
final class DynamoDbSetOrder {

    private record Keyed<K>(JsonNode member, K key) {}

    private record Order<K>(Function<String, K> key, Comparator<K> compare) {}

    private static final Order<BigDecimal> BY_VALUE = new Order<>(DynamoDbSetOrder::numberOf, Comparator.naturalOrder());
    private static final Order<byte[]> BY_BYTE = new Order<>(DynamoDbSetOrder::bytesOf, Arrays::compareUnsigned);
    private static final Order<byte[]> BY_CODE_POINT = new Order<>(DynamoDbSetOrder::utf8Of, Arrays::compareUnsigned);

    private DynamoDbSetOrder() {}

    /** Returns the members in DynamoDB's order, or the input itself when it is not an array. */
    static JsonNode sorted(String setType, JsonNode members) {
        if (!members.isArray()) {
            return members;
        }
        return sortedBy(members, orderOf(setType));
    }

    /** Returns the members as DynamoDB holds them at the first repeat, or null when none repeats. */
    static List<JsonNode> orderAtFirstRepeat(String setType, JsonNode members) {
        return orderAtFirstRepeat(members, orderOf(setType));
    }

    private static Order<?> orderOf(String setType) {
        return switch (setType) {
            case "NS" -> BY_VALUE;
            case "BS" -> BY_BYTE;
            default -> BY_CODE_POINT;
        };
    }

    // The members before the repeat are sorted. The repeat and the rest stay as sent.
    private static <K> List<JsonNode> orderAtFirstRepeat(JsonNode members, Order<K> order) {
        List<JsonNode> sent = new ArrayList<>(members.size());
        members.forEach(sent::add);
        Map<K, JsonNode> sorted = new TreeMap<>(order.compare());
        for (int i = 0; i < sent.size(); i++) {
            if (sorted.putIfAbsent(order.key().apply(sent.get(i).asText()), sent.get(i)) != null) {
                List<JsonNode> held = new ArrayList<>(sorted.values());
                held.addAll(sent.subList(i, sent.size()));
                return held;
            }
        }
        return null;
    }

    private static <K> ArrayNode sortedBy(JsonNode members, Order<K> order) {
        List<Keyed<K>> keyed = new ArrayList<>(members.size());
        for (JsonNode member : members) {
            keyed.add(new Keyed<>(member, order.key().apply(member.asText())));
        }
        keyed.sort(Comparator.comparing(Keyed::key, order.compare()));
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
