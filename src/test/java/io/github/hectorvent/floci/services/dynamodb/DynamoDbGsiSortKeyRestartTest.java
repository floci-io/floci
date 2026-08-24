package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-protocol reproduction for floci-io/floci#2415: a table whose GSI (or LSI, or own primary
 * key) has a RANGE key must survive a restart. Before the fix, {@code TableDefinition}'s Jackson
 * round-trip threw on any such table; {@code PersistentStorage.load()} swallows that as an IOException,
 * quarantines the file, and comes back up with zero tables in the whole store, not just the
 * offending one.
 *
 * <p>The GSI case here matches the issue's own reproduction (base table HASH-only, GSI with a
 * HASH+RANGE key schema). The LSI and base-table-composite-key cases share the identical root
 * cause (getSortKeyNames() added to all three model classes together in #2114) and are covered at
 * the model level by {@code SortKeyNamesJacksonRoundTripTest}.
 */
class DynamoDbGsiSortKeyRestartTest {

    private static final String TABLE = "con-gsi";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();

    /** A store backed by a real file, so a "restart" is just reading it again. */
    private StorageBackend<String, TableDefinition> diskStore(Path file) {
        PersistentStorage<String, TableDefinition> store =
                new PersistentStorage<>(file, new TypeReference<Map<String, TableDefinition>>() { });
        store.load();
        return store;
    }

    private DynamoDbJsonHandler handlerFor(StorageBackend<String, TableDefinition> store) {
        DynamoDbService service = new DynamoDbService(
                store, null, new RegionResolver(REGION, "000000000000"), null, null);
        return new DynamoDbJsonHandler(service, null, null, mapper);
    }

    private ObjectNode createTableWithGsiSortKeyRequest() {
        ObjectNode req = mapper.createObjectNode();
        req.put("TableName", TABLE);
        req.putArray("KeySchema").addObject().put("AttributeName", "PK").put("KeyType", "HASH");
        var attrDefs = req.putArray("AttributeDefinitions");
        attrDefs.addObject().put("AttributeName", "PK").put("AttributeType", "S");
        attrDefs.addObject().put("AttributeName", "GSI1PK").put("AttributeType", "S");
        attrDefs.addObject().put("AttributeName", "GSI1SK").put("AttributeType", "S");
        req.put("BillingMode", "PAY_PER_REQUEST");
        ObjectNode gsi = req.putArray("GlobalSecondaryIndexes").addObject();
        gsi.put("IndexName", "GSI1");
        var gsiKeySchema = gsi.putArray("KeySchema");
        gsiKeySchema.addObject().put("AttributeName", "GSI1PK").put("KeyType", "HASH");
        gsiKeySchema.addObject().put("AttributeName", "GSI1SK").put("KeyType", "RANGE");
        gsi.putObject("Projection").put("ProjectionType", "ALL");
        return req;
    }

    @Test
    void tableWithGsiRangeKeySurvivesARestart(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("dynamodb-tables.json");
        StorageBackend<String, TableDefinition> store = diskStore(file);

        handlerFor(store).handle("CreateTable", createTableWithGsiSortKeyRequest(), REGION);

        StorageBackend<String, TableDefinition> reopened = diskStore(file);
        assertTrue(reopened.get(REGION + "::" + TABLE).isPresent(),
                "a table with a GSI sort key must still be there after a restart");
    }

    @Test
    void otherTablesSurviveEvenWhenOneHasAGsiRangeKey(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("dynamodb-tables.json");
        StorageBackend<String, TableDefinition> store = diskStore(file);
        DynamoDbJsonHandler handler = handlerFor(store);

        handler.handle("CreateTable", createTableWithGsiSortKeyRequest(), REGION);
        ObjectNode plainTable = mapper.createObjectNode();
        plainTable.put("TableName", "plain-table");
        plainTable.putArray("KeySchema").addObject().put("AttributeName", "id").put("KeyType", "HASH");
        plainTable.putArray("AttributeDefinitions").addObject()
                .put("AttributeName", "id").put("AttributeType", "S");
        handler.handle("CreateTable", plainTable, REGION);

        StorageBackend<String, TableDefinition> reopened = diskStore(file);
        assertTrue(reopened.get(REGION + "::" + "plain-table").isPresent(),
                "an unrelated table must not be wiped out by a sibling table's GSI sort key");
    }
}
