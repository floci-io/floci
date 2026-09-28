package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.Map;

/**
 * A transaction charges the table twice the normal rate. Each index keeps the normal write
 * rate. A ConditionCheck is charged as a write of the item it checks. A same-token replay
 * reports a transactional read of each member instead, sized on the images of the first call.
 */
final class DynamoDbTransactCapacity {

    private static final int WRITE_UNIT_BYTES = 1024;
    private static final int READ_UNIT_BYTES = 4096;

    private DynamoDbTransactCapacity() {}

    static DynamoDbWriteCapacity.Cost write(TableDefinition table, JsonNode oldItem, JsonNode newItem) {
        DynamoDbWriteCapacity.Cost cost = DynamoDbWriteCapacity.forWrite(table, oldItem, newItem);
        return cost.withTable(cost.table() * 2);
    }

    static DynamoDbWriteCapacity.Cost conditionCheck(JsonNode item) {
        return tableOnly(2 * units(item, WRITE_UNIT_BYTES));
    }

    static DynamoDbWriteCapacity.Cost read(JsonNode oldItem, JsonNode newItem) {
        return tableOnly(2 * Math.max(units(oldItem, READ_UNIT_BYTES), units(newItem, READ_UNIT_BYTES)));
    }

    private static DynamoDbWriteCapacity.Cost tableOnly(long units) {
        return new DynamoDbWriteCapacity.Cost(units, Map.of(), Map.of());
    }

    private static long units(JsonNode item, int unitBytes) {
        if (item == null) {
            return 1;
        }
        return Math.max(1, (DynamoDbItemSize.calculateItemSize(item) + unitBytes - 1) / unitBytes);
    }
}
