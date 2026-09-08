package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Computes the write ConsumedCapacity of a single item write the way DynamoDB bills it.
 * The table is charged one unit per 1KB of the larger image. Each secondary index is
 * charged by how the write changes what the index stores, at one unit per 1KB of the
 * stored view: one view write for an insert, an in-place update or a delete, two view
 * writes when the index key changes (delete plus insert), and nothing when the stored
 * view is unchanged or the item never touches the index.
 */
final class DynamoDbWriteCapacity {

    private DynamoDbWriteCapacity() {}

    record Cost(double table, Map<String, Double> gsi, Map<String, Double> lsi) {

        private static final Cost ZERO = new Cost(0, Map.of(), Map.of());

        static Cost zero() {
            return ZERO;
        }

        double total() {
            var sum = table;
            for (var units : gsi.values()) sum += units;
            for (var units : lsi.values()) sum += units;
            return sum;
        }

        Cost plus(Cost other) {
            var mergedGsi = new LinkedHashMap<>(gsi);
            other.gsi().forEach((name, units) -> mergedGsi.merge(name, units, Double::sum));
            var mergedLsi = new LinkedHashMap<>(lsi);
            other.lsi().forEach((name, units) -> mergedLsi.merge(name, units, Double::sum));
            return new Cost(table + other.table(), mergedGsi, mergedLsi);
        }
    }

    /** oldItem and newItem are the stored images before and after the write; either may be null. */
    static Cost forWrite(TableDefinition table, JsonNode oldItem, JsonNode newItem) {
        var tableUnits = (double) Math.max(1, Math.max(sizeUnits(oldItem), sizeUnits(newItem)));
        var gsiUnits = new LinkedHashMap<String, Double>();
        var gsis = table.getGlobalSecondaryIndexes();
        if (gsis != null) {
            for (var gsi : gsis) {
                var units = indexUnits(table, gsi.getKeySchema(), gsi.getProjectionType(),
                        gsi.getNonKeyAttributes(), oldItem, newItem);
                if (units > 0) {
                    gsiUnits.put(gsi.getIndexName(), units);
                }
            }
        }
        var lsiUnits = new LinkedHashMap<String, Double>();
        var lsis = table.getLocalSecondaryIndexes();
        if (lsis != null) {
            for (var lsi : lsis) {
                var units = indexUnits(table, lsi.getKeySchema(), lsi.getProjectionType(),
                        lsi.getNonKeyAttributes(), oldItem, newItem);
                if (units > 0) {
                    lsiUnits.put(lsi.getIndexName(), units);
                }
            }
        }
        return new Cost(tableUnits, gsiUnits, lsiUnits);
    }

    private static double indexUnits(TableDefinition table, List<KeySchemaElement> keySchema,
                                     String projectionType, List<String> nonKeyAttributes,
                                     JsonNode oldItem, JsonNode newItem) {
        if (keySchema == null) {
            return 0;
        }
        var oldView = indexView(table, keySchema, projectionType, nonKeyAttributes, oldItem);
        var newView = indexView(table, keySchema, projectionType, nonKeyAttributes, newItem);
        if (oldView == null) {
            if (newView == null) {
                return 0;
            }
            return sizeUnits(newView);
        }
        if (newView == null) {
            return sizeUnits(oldView);
        }
        if (indexKeyChanged(keySchema, oldItem, newItem)) {
            return sizeUnits(oldView) + sizeUnits(newView);
        }
        if (oldView.equals(newView)) {
            return 0;
        }
        return Math.max(sizeUnits(oldView), sizeUnits(newView));
    }

    /**
     * The projection of the item the index stores, or null when the item is absent from
     * the index because it lacks an index key attribute (sparse index behavior).
     */
    private static JsonNode indexView(TableDefinition table, List<KeySchemaElement> keySchema,
                                      String projectionType, List<String> nonKeyAttributes,
                                      JsonNode item) {
        if (item == null) {
            return null;
        }
        for (var element : keySchema) {
            if (!item.has(element.getAttributeName())) {
                return null;
            }
        }
        if (projectionType == null || "ALL".equals(projectionType)) {
            return item;
        }
        var keep = new LinkedHashSet<String>();
        keep.add(table.getPartitionKeyName());
        if (table.getSortKeyName() != null) {
            keep.add(table.getSortKeyName());
        }
        for (var element : keySchema) {
            keep.add(element.getAttributeName());
        }
        if ("INCLUDE".equals(projectionType) && nonKeyAttributes != null) {
            keep.addAll(nonKeyAttributes);
        }
        var view = JsonNodeFactory.instance.objectNode();
        for (var name : keep) {
            if (item.has(name)) {
                view.set(name, item.get(name));
            }
        }
        return view;
    }

    private static boolean indexKeyChanged(List<KeySchemaElement> keySchema,
                                           JsonNode oldItem, JsonNode newItem) {
        for (var element : keySchema) {
            var name = element.getAttributeName();
            if (!oldItem.get(name).equals(newItem.get(name))) {
                return true;
            }
        }
        return false;
    }

    private static long sizeUnits(JsonNode image) {
        if (image == null) {
            return 0;
        }
        return Math.max(1, (DynamoDbItemSize.calculateItemSize(image) + 1023) / 1024);
    }
}
