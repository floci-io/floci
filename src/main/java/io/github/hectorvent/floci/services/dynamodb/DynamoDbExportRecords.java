package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPointInTimeRecovery.ItemChange;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The records of an incremental export. AWS sorts the attribute names and set members in them. */
final class DynamoDbExportRecords {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** AWS sorts by code point, which String.compareTo does not do above U+FFFF. */
    private static final Comparator<String> CODE_POINT_ORDER =
            (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());

    private DynamoDbExportRecords() {}

    /** A record for each item whose value at the end differs from its value at the start. */
    static List<JsonNode> records(List<ItemChange> changes, TableDefinition table, boolean newImageOnly) {
        List<String> keyNames = new ArrayList<>();
        keyNames.add(table.getPartitionKeyName());
        if (table.getSortKeyName() != null) {
            keyNames.add(table.getSortKeyName());
        }
        List<JsonNode> records = new ArrayList<>();
        for (ItemChange change : changes) {
            ObjectNode oldImage = change.oldItem() != null ? sortedAttributes(change.oldItem()) : null;
            ObjectNode newImage = change.newItem() != null ? sortedAttributes(change.newItem()) : null;
            if (Objects.equals(oldImage, newImage)) {
                continue;
            }
            JsonNode item = newImage != null ? newImage : oldImage;
            ObjectNode record = NODES.objectNode();
            record.putObject("Metadata").putObject("WriteTimestampMicros")
                    .put("N", String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, change.lastWrite())));
            ObjectNode keys = NODES.objectNode();
            for (String keyName : keyNames) {
                keys.set(keyName, item.get(keyName));
            }
            record.set("Keys", sortedAttributes(keys));
            if (oldImage != null && !newImageOnly) {
                record.set("OldImage", oldImage);
            }
            if (newImage != null) {
                record.set("NewImage", newImage);
            }
            records.add(record);
        }
        return records;
    }

    private static ObjectNode sortedAttributes(JsonNode attributes) {
        Map<String, JsonNode> byName = new TreeMap<>(CODE_POINT_ORDER);
        attributes.properties().forEach(attribute -> byName.put(attribute.getKey(), attribute.getValue()));
        ObjectNode sorted = NODES.objectNode();
        byName.forEach((name, value) -> sorted.set(name, sortedValue(value)));
        return sorted;
    }

    private static JsonNode sortedValue(JsonNode value) {
        Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
        JsonNode content = typed.getValue();
        return switch (typed.getKey()) {
            case "M" -> NODES.objectNode().set("M", sortedAttributes(content));
            case "L" -> {
                ArrayNode members = NODES.arrayNode();
                content.forEach(member -> members.add(sortedValue(member)));
                yield NODES.objectNode().set("L", members);
            }
            case "SS", "NS", "BS" -> {
                String setType = typed.getKey();
                yield NODES.objectNode().set(setType, DynamoDbSetOrder.sorted(setType, content));
            }
            default -> value;
        };
    }
}
