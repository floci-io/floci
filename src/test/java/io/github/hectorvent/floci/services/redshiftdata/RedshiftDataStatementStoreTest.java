package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import static org.mockito.Mockito.*;

class RedshiftDataStatementStoreTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receiptSweepFailureDoesNotPreventOwnedStatementEviction(boolean receiptFailure) {
        StorageFactory factory = mock(StorageFactory.class);
        AccountAwareStorageBackend<RedshiftDataStatementStore.StoredStatement> backend =
                AccountAwareStorageBackend.inMemory("000000000000");
        when(factory.<RedshiftDataStatementStore.StoredStatement>create(eq("redshift-data"),
                eq("statements.json"), any())).thenReturn(backend);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftData().resultTtlHours()).thenReturn(24);
        RedshiftDataIdempotencyStore receipts = mock(RedshiftDataIdempotencyStore.class);
        if (receiptFailure) {
            doThrow(new UncheckedIOException(new IOException("receipt replacement failed")))
                    .when(receipts).sweep();
        }
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(factory, config, receipts);
        Instant now = Instant.now();
        store.putForAccount("111111111111", statement("same-id", now.minus(Duration.ofHours(48))));
        store.putForAccount("222222222222", statement("same-id", now.minus(Duration.ofHours(1))));
        RedshiftDataStatementStore.StoredStatement active = statement("active", now.minus(Duration.ofHours(48)));
        active.status = RedshiftDataStatementStore.Status.STARTED;
        store.putForAccount("111111111111", active);

        store.sweep();

        verify(receipts).sweep();
        assertNull(store.getForAccount("111111111111", "same-id"));
        assertNotNull(store.getForAccount("222222222222", "same-id"));
        assertNotNull(store.getForAccount("111111111111", "active"));
    }

    @Test
    void recoveryFailsInterruptedWorkWithoutChangingTerminalResults() {
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        RedshiftDataStatementStore.StoredStatement active = statement("active", Instant.now());
        active.status = RedshiftDataStatementStore.Status.STARTED;
        store.put(active);
        store.put(statement("finished", Instant.now()));
        store.start();
        try {
            assertEquals(RedshiftDataStatementStore.Status.FAILED, store.get("active").status);
            assertEquals("Execution interrupted by emulator restart", store.get("active").error);
            assertEquals(RedshiftDataStatementStore.Status.FINISHED, store.get("finished").status);
        } finally {
            store.stop();
        }
    }

    @Test
    void sweepRetainsActiveStatementsOlderThanTtl() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.fixed(now, ZoneOffset.UTC));
        RedshiftDataStatementStore.StoredStatement active = statement("active", now.minus(Duration.ofHours(25)));
        active.status = RedshiftDataStatementStore.Status.SUBMITTED;
        store.put(active);
        store.sweep();
        assertNotNull(store.get("active"));
    }

    @Test
    void storedSnapshotDoesNotChangeWhenWorkerMutatesItsRecord() {
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        RedshiftDataStatementStore.StoredStatement original = statement("snapshot", Instant.now());
        original.status = RedshiftDataStatementStore.Status.STARTED;
        store.put(original);
        original.status = RedshiftDataStatementStore.Status.FINISHED;
        assertEquals(RedshiftDataStatementStore.Status.STARTED, store.get("snapshot").status);
        store.get("snapshot").status = RedshiftDataStatementStore.Status.FAILED;
        assertEquals(RedshiftDataStatementStore.Status.STARTED, store.get("snapshot").status);
    }

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
        original.queryParameters = List.of(new RedshiftDataStatementStore.StoredParameter("param1", "val1"));

        RedshiftDataStatementStore.StoredStatement snapshot = original.snapshot();
        assertEquals(1, snapshot.queryParameters.size());
        assertEquals("param1", snapshot.queryParameters.get(0).name());
        assertEquals("val1", snapshot.queryParameters.get(0).value());

        RedshiftDataStatementStore.StoredStatement summary = original.summary();
        assertEquals(1, summary.queryParameters.size());
        assertEquals("param1", summary.queryParameters.get(0).name());
        assertEquals("val1", summary.queryParameters.get(0).value());

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
        assertNotNull(restored.queryParameters);
        assertEquals(1, restored.queryParameters.size());
        assertEquals("param1", restored.queryParameters.get(0).name());
        assertEquals("val1", restored.queryParameters.get(0).value());
    }
}
