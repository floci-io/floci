package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDataStatementStoreTest {

    private static RedshiftDataStatementStore.StoredStatement statement(String id, Instant createdAt) {
        RedshiftDataStatementStore.StoredStatement s = new RedshiftDataStatementStore.StoredStatement();
        s.id = id;
        s.createdAt = createdAt;
        s.updatedAt = createdAt;
        s.status = RedshiftDataStatementStore.Status.FINISHED;
        return s;
    }

    @Test
    void putThenGet() {
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        store.put(statement("a", Instant.now()));
        assertNotNull(store.get("a"));
        assertNull(store.get("missing"));
    }

    @Test
    void sweepEvictsEntriesOlderThanTtl() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, fixed);
        store.put(statement("old", now.minus(Duration.ofHours(25))));
        store.put(statement("fresh", now.minus(Duration.ofHours(1))));

        store.sweep();

        assertNull(store.get("old"));
        assertNotNull(store.get("fresh"));
    }

    @Test
    void clearEmptiesStore() {
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        store.put(statement("a", Instant.now()));
        store.clear();
        assertNull(store.get("a"));
    }

    @Test
    void valuesReturnsEveryStoredStatement() {
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        store.put(statement("a", Instant.now()));
        store.put(statement("b", Instant.now()));
        assertEquals(2, store.values().size());
    }

    /**
     * The store is now backed by StorageFactory, so a persistent-mode backend serializes
     * StoredStatement to JSON and reads it back through the same TypeReference the backend uses.
     * This exercises the reflection / field-visibility annotations plus the ArrayNode, enum,
     * Instant, and nested sub-statement fields with the exact mapper PersistentStorage builds.
     */
    @Test
    void storedStatementRoundTripsThroughThePersistentStorageMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ArrayNode columnMetadata = mapper.createArrayNode();
        columnMetadata.addObject().put("name", "id").put("typeName", "int4").put("nullable", 1);
        ArrayNode row = mapper.createArrayNode();
        row.addObject().put("longValue", 7L);
        row.addObject().put("stringValue", "seven");

        RedshiftDataStatementStore.StoredStatement sub = new RedshiftDataStatementStore.StoredStatement();
        sub.id = "parent:1";
        sub.sql = "insert into t values (7)";
        sub.status = RedshiftDataStatementStore.Status.FINISHED;
        sub.createdAt = Instant.parse("2026-09-08T10:00:00Z");
        sub.updatedAt = sub.createdAt;

        RedshiftDataStatementStore.StoredStatement original = new RedshiftDataStatementStore.StoredStatement();
        original.id = "parent";
        original.sql = "insert into t values (7); select * from t";
        original.sqls = List.of("insert into t values (7)", "select * from t");
        original.batch = true;
        original.statementName = "load";
        original.clusterIdentifier = "wh";
        original.database = "dev";
        original.dbUser = "admin";
        original.resultFormat = "CSV";
        original.status = RedshiftDataStatementStore.Status.FINISHED;
        original.error = null;
        original.createdAt = Instant.parse("2026-09-08T10:00:00Z");
        original.updatedAt = Instant.parse("2026-09-08T10:00:01Z");
        original.durationNanos = 123_456L;
        original.hasResultSet = true;
        original.resultRows = 1;
        original.resultSize = 42;
        original.columnMetadata = columnMetadata;
        original.rows = List.of(row);
        original.subStatements = List.of(sub);

        String json = mapper.writeValueAsString(Map.of("parent", original));
        Map<String, RedshiftDataStatementStore.StoredStatement> back =
                mapper.readValue(json, new TypeReference<Map<String, RedshiftDataStatementStore.StoredStatement>>() {});

        RedshiftDataStatementStore.StoredStatement restored = back.get("parent");
        assertNotNull(restored);
        assertEquals("parent", restored.id);
        assertEquals(List.of("insert into t values (7)", "select * from t"), restored.sqls);
        assertTrue(restored.batch);
        assertEquals("CSV", restored.resultFormat);
        assertEquals(RedshiftDataStatementStore.Status.FINISHED, restored.status);
        assertEquals(Instant.parse("2026-09-08T10:00:01Z"), restored.updatedAt);
        assertEquals(123_456L, restored.durationNanos);
        assertTrue(restored.hasResultSet);
        assertEquals("id", restored.columnMetadata.get(0).get("name").asText());
        assertEquals(1, restored.rows.size());
        assertEquals(7L, restored.rows.get(0).get(0).get("longValue").asLong());
        assertEquals("seven", restored.rows.get(0).get(1).get("stringValue").asText());
        assertEquals(1, restored.subStatements.size());
        assertEquals("parent:1", restored.subStatements.get(0).id);
        assertEquals(RedshiftDataStatementStore.Status.FINISHED, restored.subStatements.get(0).status);
    }
}
