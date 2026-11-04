package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalLifecycleTest {
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:c",
            "dev", List.of(), false);
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(SESSION.accountId(),
            SESSION.clusterKey(), "dev", "lake", "analytics", "arn:aws:iam::000000000000:role/R");
    private static final String ADD = "ALTER TABLE lake.sales ADD PARTITION (dt='2026-10-17') LOCATION 's3://bucket/day/'";
    private final ExternalCatalogRegistry registry = mock(ExternalCatalogRegistry.class);
    private final ExternalTableMaterializer materializer = mock(ExternalTableMaterializer.class);
    private final GlueService glue = mock(GlueService.class);
    private final BackendSql backend = mock(BackendSql.class);
    private final ExternalMetadataWriter metadata = spy(new ExternalMetadataWriter(glue, new ObjectMapper()));
    private final ExternalCatalogLocks catalogLocks = new ExternalCatalogLocks();
    private final SpectrumQueryPreparation preparation = new SpectrumQueryPreparation(registry, materializer,
            new ExternalStatementParser(), glue, metadata, catalogLocks);
    private Table table;

    @BeforeEach
    void setUp() {
        when(registry.list(SESSION.accountId(), SESSION.clusterKey(), "dev")).thenReturn(List.of(BINDING));
        Column key = new Column();
        key.setName("dt");
        key.setType("string");
        table = new Table();
        table.setName("sales");
        table.setPartitionKeys(List.of(key));
        table.setStorageDescriptor(new StorageDescriptor());
        when(glue.getTable("analytics", "sales")).thenReturn(table);
        doNothing().when(metadata).refresh(any(), anyString(), any());
    }

    @Test
    void registersPartitionAndInvalidatesMaterializedRows() {
        assertTrue(preparation.handlesDdl(ADD, SESSION));
        assertTrue(preparation.prepare(ADD, SESSION, backend));
        ArgumentCaptor<Partition> captured = ArgumentCaptor.forClass(Partition.class);
        verify(glue).createPartition(eq("analytics"), eq("sales"), captured.capture());
        assertEquals(List.of("2026-10-17"), captured.getValue().getValues());
        assertEquals("s3://bucket/day/", captured.getValue().getStorageDescriptor().getLocation());
        verify(materializer).invalidateTable(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(metadata).refresh(backend, SESSION.accountId(), BINDING);
    }

    @Test
    void duplicatePartitionIsAnErrorAndIfNotExistsPreservesItsLocation() {
        Partition existing = new Partition();
        existing.setValues(List.of("2026-10-17"));
        when(glue.getPartitions("analytics", "sales")).thenReturn(List.of(existing));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(ADD, SESSION, backend));
        assertEquals("42710", error.sqlState());
        assertTrue(preparation.prepare(ADD.replace("ADD PARTITION", "ADD IF NOT EXISTS PARTITION"), SESSION, backend));
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void validatesEveryPartitionBeforeWritingAny() {
        String sql = ADD + " PARTITION (wrong='x') LOCATION 's3://bucket/invalid/'";
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(sql, SESSION, backend));
        assertEquals("22023", error.sqlState());
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void duplicateWithinTheSameRequestDoesNotPartiallyRegister() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(ADD + " PARTITION (dt='2026-10-17') LOCATION 's3://bucket/other/'", SESSION, backend));
        assertEquals("42710", error.sqlState());
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void dropTableRemovesGlueDefinitionAndColdOrCachedBackendRelation() {
        assertTrue(preparation.handlesDdl("DROP TABLE lake.sales", SESSION));
        assertTrue(preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"lake\".\"sales\" RESTRICT");
        verify(glue).deleteTable("analytics", "sales");
        verify(materializer).forget(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(metadata).refresh(backend, SESSION.accountId(), BINDING);
        verify(materializer, never()).ensureCurrent(any(), any(), any(), anyString());
    }

    @Test
    void failedDependencyCheckKeepsGlueTable() {
        doThrow(new SpectrumReadException("2BP01", "dependent view")).when(backend).execute(anyString());
        assertThrows(SpectrumReadException.class, () -> preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        verify(glue, never()).deleteTable(anyString(), anyString());
        verifyNoInteractions(materializer);
    }

    @Test
    void dropMissingTableRequiresIfExistsAndCleansStaleCache() {
        when(glue.getTable("analytics", "sales"))
                .thenThrow(new AwsException("EntityNotFoundException", "gone", 400));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        assertEquals("42P01", error.sqlState());
        verifyNoInteractions(backend);
        assertTrue(preparation.prepare("DROP TABLE IF EXISTS lake.sales CASCADE", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"lake\".\"sales\" CASCADE");
        verify(materializer).forget(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(glue, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void restrictChecksGlueTablesThatHaveNeverBeenLoaded() {
        when(glue.getTables("analytics")).thenReturn(List.of(table));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP SCHEMA lake", SESSION, backend));
        assertEquals("2BP01", error.sqlState());
        verify(backend, never()).execute(startsWith("DROP SCHEMA"));
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cascadeUnregistersSchemaWithoutDeletingGlueDatabaseOrTables() {
        assertTrue(preparation.handlesDdl("DROP SCHEMA lake CASCADE", SESSION));
        assertTrue(preparation.prepare("DROP SCHEMA lake CASCADE", SESSION, backend));
        verify(backend).execute("DROP SCHEMA \"lake\" CASCADE; SELECT floci_internal.purge_external_schema('lake')");
        verify(registry).unbind(SESSION.accountId(), SESSION.clusterKey(), "dev", "lake");
        verify(materializer).forgetSchema(SESSION.clusterKey(), "dev", "lake");
        verify(glue, never()).deleteDatabase(anyString());
        verify(glue, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void failedSchemaDropRetainsBindingAndCache() {
        doThrow(new SpectrumReadException("42501", "not owner")).when(backend).execute(anyString());
        assertThrows(SpectrumReadException.class, () -> preparation.prepare("DROP SCHEMA lake CASCADE", SESSION, backend));
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(materializer);
    }

    @Test
    void rejectsLifecycleInsideTransactionsBeforeMutatingStores() {
        SpectrumSession transaction = new SpectrumSession(SESSION.accountId(), SESSION.clusterKey(), "dev", List.of(), true);
        for (String sql : List.of(ADD, "DROP TABLE lake.sales", "DROP SCHEMA lake CASCADE")) {
            assertTrue(preparation.handlesDdl(sql, transaction));
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> preparation.prepare(sql, transaction, backend));
            assertEquals("25001", error.sqlState());
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void leavesInternalLifecycleToPostgres() {
        for (String sql : List.of("DROP TABLE public.sales", "DROP SCHEMA public CASCADE",
                "ALTER TABLE public.sales ADD COLUMN total INTEGER")) {
            assertFalse(preparation.handlesDdl(sql, SESSION));
            assertFalse(preparation.prepare(sql, SESSION, backend));
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void malformedOrRepeatedPartitionKeysNeverReachGlue() {
        for (String keys : List.of("dt='x', garbage", "dt='x', dt='y'", "dt='x' garbage")) {
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                    "ALTER TABLE lake.sales ADD PARTITION (" + keys + ") LOCATION 's3://bucket/day/'", SESSION, backend));
            assertEquals("42601", error.sqlState());
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void ifExistsDoesNotSuppressAccessDenied() {
        when(glue.getTable("analytics", "sales"))
                .thenThrow(new AwsException("AccessDeniedException", "denied", 403));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP TABLE IF EXISTS lake.sales", SESSION, backend));
        assertEquals("42501", error.sqlState());
        verifyNoInteractions(backend, materializer);
    }

    @Test
    void quotesSchemaAndTableNamesBeforeSendingBackendDdl() {
        ExternalSchemaBinding quoted = new ExternalSchemaBinding(SESSION.accountId(), SESSION.clusterKey(),
                "dev", "a\"b", "analytics", BINDING.iamRoleArn());
        when(registry.list(SESSION.accountId(), SESSION.clusterKey(), "dev")).thenReturn(List.of(quoted));
        assertTrue(preparation.prepare("DROP TABLE IF EXISTS \"a\"\"b\".\"sales\"", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"a\"\"b\".\"sales\" RESTRICT");
    }

    @Test
    void backendAuthorizationFailsBeforeAnyCatalogMutation() {
        doThrow(new SpectrumReadException("42501", "not owner")).when(backend).execute(anyString());
        for (String sql : List.of(ADD, "DROP TABLE lake.sales")) {
            SpectrumReadException error = assertThrows(SpectrumReadException.class,
                    () -> preparation.prepare(sql, SESSION, backend));
            assertEquals("42501", error.sqlState());
        }
        verify(glue, never()).createPartition(anyString(), anyString(), any());
        verify(glue, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void rejectsCaseVariantsOfTheSamePartitionKey() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "ALTER TABLE lake.sales ADD PARTITION (\"DT\"='upper', dt='lower') LOCATION 's3://bucket/day/'",
                SESSION, backend));
        assertEquals("42601", error.sqlState());
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void checksSchemaOwnershipBeforeReadingGlueTables() {
        doThrow(new SpectrumReadException("42501", "not owner")).when(backend).execute(anyString());
        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> preparation.prepare("DROP SCHEMA lake RESTRICT", SESSION, backend));
        assertEquals("42501", error.sqlState());
        verifyNoInteractions(glue);
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dropsLocalSchemaWhenGlueDatabaseIsAlreadyGone() {
        when(glue.getTables("analytics")).thenThrow(new AwsException("EntityNotFoundException", "gone", 400));
        assertTrue(preparation.prepare("DROP SCHEMA IF EXISTS lake", SESSION, backend));
        verify(backend).execute("DROP SCHEMA IF EXISTS \"lake\" RESTRICT; SELECT floci_internal.purge_external_schema('lake')");
        verify(registry).unbind(SESSION.accountId(), SESSION.clusterKey(), "dev", "lake");
        verify(materializer).forgetSchema(SESSION.clusterKey(), "dev", "lake");
    }

    @Test
    void schemaDropDoesNotSuppressOtherGlueFailures() {
        when(glue.getTables("analytics")).thenThrow(new AwsException("AccessDeniedException", "denied", 403));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP SCHEMA IF EXISTS lake", SESSION, backend));
        assertEquals("42501", error.sqlState());
        verify(backend, never()).execute(startsWith("DROP SCHEMA"));
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rollsBackOnlyNewPartitionsWhenLaterWriteFails() {
        Partition existing = new Partition();
        existing.setValues(List.of("existing"));
        when(glue.getPartitions("analytics", "sales")).thenReturn(List.of(existing));
        doAnswer(invocation -> {
            Partition partition = invocation.getArgument(2);
            if (partition.getValues().equals(List.of("fail"))) {
                throw new AwsException("InternalServiceException", "write failed", 500);
            }
            return null;
        }).when(glue).createPartition(anyString(), anyString(), any());
        String sql = ADD.replace("ADD PARTITION", "ADD IF NOT EXISTS PARTITION")
                + " PARTITION (dt='existing') LOCATION 's3://bucket/old/'"
                + " PARTITION (dt='fail') LOCATION 's3://bucket/fail/'";
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(sql, SESSION, backend));
        assertEquals("write failed", error.getMessage());
        verify(glue).deletePartition("analytics", "sales", List.of("2026-10-17"));
        verify(glue, never()).deletePartition("analytics", "sales", List.of("existing"));
        verify(glue, never()).deletePartition("analytics", "sales", List.of("fail"));
    }

    @Test
    void rollsBackPartitionsWhenMetadataRefreshFailsWithoutMaskingTheFailure() {
        SpectrumReadException failure = new SpectrumReadException("58030", "refresh failed");
        doThrow(failure).when(metadata).refresh(backend, SESSION.accountId(), BINDING);
        doThrow(new AwsException("InternalServiceException", "cleanup failed", 500)).when(glue)
                .deletePartition("analytics", "sales", List.of("2026-10-17"));
        assertSame(failure, assertThrows(SpectrumReadException.class, () -> preparation.prepare(ADD, SESSION, backend)));
        verify(glue).deletePartition("analytics", "sales", List.of("2026-10-17"));
        assertEquals(1, failure.getSuppressed().length);
        verify(materializer).invalidateTable(SESSION.clusterKey(), "dev", "lake", "sales");
    }

    @Test
    void implicitExtendedTransactionTimesOutWhileCatalogMutationIsWaiting() throws Exception {
        when(backend.permitsCachePublication()).thenReturn(false);
        ExternalCatalogLocks shortLocks = new ExternalCatalogLocks(Duration.ofMillis(20));
        SpectrumQueryPreparation timedPreparation = new SpectrumQueryPreparation(registry, materializer,
                new ExternalStatementParser(), glue, metadata, shortLocks);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ExternalCatalogLocks.Guard mutation = shortLocks.write(BINDING);
            try {
                Future<Boolean> read = executor.submit(
                        () -> timedPreparation.prepare("SELECT * FROM lake.sales", SESSION, backend));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> read.get(5, TimeUnit.SECONDS));
                SpectrumReadException timeout = assertInstanceOf(SpectrumReadException.class, failure.getCause());
                assertEquals("58030", timeout.sqlState());
                verifyNoInteractions(materializer, glue);
            } finally {
                mutation.close();
            }
        }
    }

    @Test
    void removesAllNewPartitionsWhenMetadataRefreshFails() {
        SpectrumReadException failure = new SpectrumReadException("58030", "refresh failed");
        doThrow(failure).when(metadata).refresh(backend, SESSION.accountId(), BINDING);
        String sql = ADD + " PARTITION (dt='2026-10-18') LOCATION 's3://bucket/next/'";

        assertSame(failure, assertThrows(SpectrumReadException.class,
                () -> preparation.prepare(sql, SESSION, backend)));

        verify(glue).deletePartition("analytics", "sales", List.of("2026-10-17"));
        verify(glue).deletePartition("analytics", "sales", List.of("2026-10-18"));
        verify(materializer).invalidateTable(SESSION.clusterKey(), "dev", "lake", "sales");
    }

    @Test
    void concurrentIfNotExistsAddsShareTheGlueTableLock() throws Exception {
        List<Partition> stored = new CopyOnWriteArrayList<>();
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        when(glue.getTable("ANALYTICS", "sales")).thenReturn(table);
        when(glue.getPartitions(anyString(), eq("sales"))).thenAnswer(invocation -> {
            List<Partition> snapshot = List.copyOf(stored);
            if (checks.incrementAndGet() == 1) {
                checking.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return snapshot;
        });
        doAnswer(invocation -> {
            stored.add(invocation.getArgument(2));
            return null;
        }).when(glue).createPartition(anyString(), anyString(), any());
        String sql = ADD.replace("ADD PARTITION", "ADD IF NOT EXISTS PARTITION");
        SpectrumSession otherSession = new SpectrumSession(SESSION.accountId(), SESSION.accountId() + ":other",
                "dev", List.of(), false);
        ExternalSchemaBinding alias = new ExternalSchemaBinding(SESSION.accountId(), otherSession.clusterKey(),
                "dev", "alias", "ANALYTICS", BINDING.iamRoleArn());
        when(registry.list(SESSION.accountId(), otherSession.clusterKey(), "dev")).thenReturn(List.of(alias));
        SpectrumQueryPreparation otherPreparation = new SpectrumQueryPreparation(registry, materializer,
                new ExternalStatementParser(), glue, metadata, catalogLocks);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> preparation.prepare(sql, SESSION, backend));
            try {
                assertTrue(checking.await(10, TimeUnit.SECONDS));
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<Boolean> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return otherPreparation.prepare(
                            sql.replace("lake.sales", "alias.sales"), otherSession, backend);
                });
                awaitBlockedOrDone(secondThread, second);
                assertFalse(second.isDone());
                release.countDown();
                assertTrue(first.get(10, TimeUnit.SECONDS));
                assertTrue(second.get(10, TimeUnit.SECONDS));
                assertEquals(1, stored.size());
            } finally {
                release.countDown();
            }
        }
    }
}
