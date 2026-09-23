package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.SearchSchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the write ConsumedCapacity model. The table setup mirrors the
 * paritysuite dynamodb-conformance index write-capacity ladder: a GSI with an
 * INCLUDE projection as the projected/non-projected lever, and an ALL-projected
 * LSI. Every charge below is a value measured on real DynamoDB by that suite.
 */
class DynamoDbWriteCapacityTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private TableDefinition table;

    @BeforeEach
    void setUp() {
        table = new TableDefinition();
        table.setKeySchema(List.of(
                new KeySchemaElement("pk", "HASH"),
                new KeySchemaElement("sk", "RANGE")));
        table.setGlobalSecondaryIndexes(List.of(new GlobalSecondaryIndex(
                "gsi-inc",
                List.of(new KeySchemaElement("gsiPk", "HASH")),
                null, "INCLUDE", List.of("proj"))));
        table.setLocalSecondaryIndexes(List.of(new LocalSecondaryIndex(
                "lsi1",
                List.of(new KeySchemaElement("pk", "HASH"),
                        new KeySchemaElement("lsiSk", "RANGE")),
                null, "ALL")));
    }

    private static ObjectNode item(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode fullItem() {
        return item("""
                {
                  "pk": {"S": "p1"}, "sk": {"S": "1"},
                  "gsiPk": {"S": "g1"}, "lsiSk": {"S": "L1"},
                  "proj": {"S": "p"}, "other": {"S": "o"}
                }
                """);
    }

    @Test
    void insertChargesTableAndEveryIndexTheItemEnters() {
        var cost = DynamoDbWriteCapacity.forWrite(table, null, fullItem());
        assertEquals(1.0, cost.table());
        assertEquals(Map.of("gsi-inc", 1.0), cost.gsi());
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(3.0, cost.total());
    }

    @Test
    void sparseInsertChargesNothingForTheIndexesItMisses() {
        var sparse = item("""
                {"pk": {"S": "p1"}, "sk": {"S": "1"}, "other": {"S": "o"}}
                """);
        var cost = DynamoDbWriteCapacity.forWrite(table, null, sparse);
        assertEquals(1.0, cost.total());
        assertTrue(cost.gsi().isEmpty(), "an item without gsiPk never enters the GSI");
        assertTrue(cost.lsi().isEmpty(), "an item without lsiSk never enters the LSI");
    }

    @Test
    void identicalOverwriteChargesNoIndexWritesAtAll() {
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), fullItem());
        assertEquals(1.0, cost.table(), "the table write is still charged");
        assertTrue(cost.gsi().isEmpty());
        assertTrue(cost.lsi().isEmpty());
    }

    @Test
    void nonProjectedAttributeChangeChargesNothingOnTheIncludeIndex() {
        var updated = fullItem();
        updated.set("other", item("""
                {"S": "o2"}
                """));
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), updated);
        assertTrue(cost.gsi().isEmpty(), "other is outside the INCLUDE projection");
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(2.0, cost.total());
    }

    @Test
    void projectedAttributeChangeChargesOneIndexWrite() {
        var updated = fullItem();
        updated.set("proj", item("""
                {"S": "p2"}
                """));
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), updated);
        assertEquals(Map.of("gsi-inc", 1.0), cost.gsi());
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(3.0, cost.total());
    }

    @Test
    void indexKeyChangeChargesDeletePlusInsert() {
        var moved = fullItem();
        moved.set("gsiPk", item("""
                {"S": "g-moved"}
                """));
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), moved);
        assertEquals(Map.of("gsi-inc", 2.0), cost.gsi());
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(4.0, cost.total());
    }

    @Test
    void removingTheIndexKeyChargesDeleteOnly() {
        var removed = fullItem();
        removed.remove("gsiPk");
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), removed);
        assertEquals(Map.of("gsi-inc", 1.0), cost.gsi());
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(3.0, cost.total());
    }

    @Test
    void lsiKeyChangeWalksTheSameLadder() {
        var moved = fullItem();
        moved.set("lsiSk", item("""
                {"S": "L2"}
                """));
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), moved);
        assertTrue(cost.gsi().isEmpty(), "lsiSk is outside the GSI projection, its view never changed");
        assertEquals(Map.of("lsi1", 2.0), cost.lsi());
        assertEquals(3.0, cost.total());
    }

    @Test
    void deleteChargesOneWritePerIndexTheItemOccupied() {
        var cost = DynamoDbWriteCapacity.forWrite(table, fullItem(), null);
        assertEquals(1.0, cost.table());
        assertEquals(Map.of("gsi-inc", 1.0), cost.gsi());
        assertEquals(Map.of("lsi1", 1.0), cost.lsi());
        assertEquals(3.0, cost.total());
    }

    @Test
    void deleteOfAMissingItemStillChargesOneTableUnit() {
        var cost = DynamoDbWriteCapacity.forWrite(table, null, null);
        assertEquals(1.0, cost.total());
    }

    @Test
    void costPlusSumsTheTableAndMergesIndexArms() {
        var indexed = DynamoDbWriteCapacity.forWrite(table, null, fullItem());
        var sparse = DynamoDbWriteCapacity.forWrite(table, null, item("""
                {"pk": {"S": "p2"}, "sk": {"S": "1"}, "other": {"S": "o"}}
                """));
        var sum = DynamoDbWriteCapacity.Cost.zero().plus(indexed).plus(sparse);
        assertEquals(2.0, sum.table());
        assertEquals(Map.of("gsi-inc", 1.0), sum.gsi());
        assertEquals(Map.of("lsi1", 1.0), sum.lsi());
        assertEquals(4.0, sum.total());
    }

    @Test
    void tableUnitsFollowTheLargerImagePerKilobyte() {
        var big = item("""
                {"pk": {"S": "p1"}, "sk": {"S": "1"}}
                """);
        big.set("filler", item("""
                {"S": "%s"}
                """.formatted("x".repeat(2500))));
        var cost = DynamoDbWriteCapacity.forWrite(table, big, fullItem());
        assertEquals(3.0, cost.table(), "the larger of the two images rounds up per 1KB");
    }

    /**
     * Two vector indexes on one attribute, differing only in projection. That pair is what
     * separates the projection term of VectorWriteRequestBytes from every other term, and it
     * mirrors the fixture the conformance suite measured against real DynamoDB.
     */
    private static TableDefinition vectorTable() {
        TableDefinition vectorIndexed = new TableDefinition();
        vectorIndexed.setKeySchema(List.of(new KeySchemaElement("pk", "HASH")));
        vectorIndexed.setVectorIndexes(List.of(
                new VectorIndex("vix", "embedding", List.of(), "ALL", List.of(), 3L, "COSINE"),
                new VectorIndex("vkeys", "embedding", List.of(), "KEYS_ONLY", List.of(), 3L, "COSINE")));
        return vectorIndexed;
    }

    private static ObjectNode vectorItem(String key) {
        return item("""
                {"pk": {"S": "%s"},
                 "embedding": {"L": [{"N": "1"}, {"N": "0"}, {"N": "0"}]}}
                """.formatted(key));
    }

    @Test
    void vectorEntryBelowTheFloorReportsTheFloor() {
        DynamoDbWriteCapacity.Cost cost =
                DynamoDbWriteCapacity.forWrite(vectorTable(), null, vectorItem("f0"));
        assertEquals(Map.of("vix", 1024.0, "vkeys", 1024.0), cost.vectorBytes());
        assertEquals(1.0, cost.total(), "bytes processed are not capacity units");
    }

    @Test
    void vectorFloorBindsPerEntryRatherThanPerWrite() {
        ObjectNode blobbed = vectorItem("3a");
        blobbed.set("blob", item("""
                {"S": "%s"}
                """.formatted("x".repeat(1500))));
        DynamoDbWriteCapacity.Cost cost = DynamoDbWriteCapacity.forWrite(vectorTable(), null, blobbed);
        // 4 for the key, 1504 for the blob, 9 for the vector's name, 4 per dimension.
        assertEquals(1529.0, cost.vectorBytes().get("vix"));
        assertEquals(1024.0, cost.vectorBytes().get("vkeys"), "KEYS_ONLY never holds the blob");
    }

    @Test
    void identicalOverwriteChargesNoVectorWrite() {
        DynamoDbWriteCapacity.Cost cost =
                DynamoDbWriteCapacity.forWrite(vectorTable(), vectorItem("a"), vectorItem("a"));
        assertTrue(cost.vectorBytes().isEmpty(), "replication is delta-based");
    }

    @Test
    void vectorIndexIsChargedOnlyWhenItProjectsTheChange() {
        ObjectNode before = vectorItem("a");
        before.set("note", item("""
                {"S": "before"}
                """));
        ObjectNode after = vectorItem("a");
        after.set("note", item("""
                {"S": "after!"}
                """));
        DynamoDbWriteCapacity.Cost cost = DynamoDbWriteCapacity.forWrite(vectorTable(), before, after);
        assertEquals(1024.0, cost.vectorBytes().get("vix"));
        assertNull(cost.vectorBytes().get("vkeys"), "the KEYS_ONLY entry did not change");
    }

    @Test
    void itemWithoutTheVectorAttributeEntersNoVectorIndex() {
        DynamoDbWriteCapacity.Cost cost = DynamoDbWriteCapacity.forWrite(vectorTable(), null, item("""
                {"pk": {"S": "plain"}, "label": {"S": "no-vector"}}
                """));
        assertTrue(cost.vectorBytes().isEmpty());
    }

    @Test
    void itemMissingTheSearchSchemaHashEntersNoVectorIndex() {
        TableDefinition partitioned = new TableDefinition();
        partitioned.setKeySchema(List.of(new KeySchemaElement("pk", "HASH")));
        partitioned.setVectorIndexes(List.of(new VectorIndex("schema", "embedding",
                List.of(new SearchSchemaElement("tenant", "HASH")), "ALL", List.of(), 3L, "COSINE")));
        DynamoDbWriteCapacity.Cost cost =
                DynamoDbWriteCapacity.forWrite(partitioned, null, vectorItem("no-tenant"));
        assertTrue(cost.vectorBytes().isEmpty());
    }
}
