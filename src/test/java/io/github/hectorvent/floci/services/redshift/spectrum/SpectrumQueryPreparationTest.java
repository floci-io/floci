package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpectrumQueryPreparationTest {
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:c",
            "dev", List.of(), false);
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(SESSION.accountId(),
            SESSION.clusterKey(), "dev", "lake", "analytics", "arn:aws:iam::000000000000:role/R");
    private ExternalCatalogRegistry registry;
    private ExternalTableMaterializer materializer;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;
    private GlueService glue;

    @BeforeEach
    void setUp() {
        registry = mock(ExternalCatalogRegistry.class);
        materializer = mock(ExternalTableMaterializer.class);
        backend = mock(BackendSql.class);
        when(registry.list(SESSION.accountId(), SESSION.clusterKey(), "dev")).thenReturn(List.of(BINDING));
        when(materializer.ensureCurrent(any(), any(), any(), anyString()))
                .thenReturn(ExternalTableMaterializer.Outcome.CURRENT);
        glue = mock(GlueService.class);
        preparation = new SpectrumQueryPreparation(registry, materializer, new ExternalStatementParser(),
                glue, mock(ExternalMetadataWriter.class));
    }

    @Test
    void glueDdlFailureUsesSqlErrorInsteadOfEscapingTheWireHandler() {
        doThrow(new AwsException("AlreadyExistsException", "table already exists", 400))
                .when(glue).createTable(anyString(), any());
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE EXTERNAL TABLE lake.sales (id INTEGER) STORED AS TEXTFILE LOCATION 's3://bucket/'",
                SESSION, backend));
        assertEquals("42P07", error.sqlState());
    }

    @Test
    void transactionalSchemaCreateFailsBeforeChangingEitherStore() {
        SpectrumSession transaction = new SpectrumSession(SESSION.accountId(), SESSION.clusterKey(),
                SESSION.databaseName(), List.of(), true);
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE 'analytics' IAM_ROLE '"
                        + BINDING.iamRoleArn() + "'", transaction, backend));
        assertEquals("0A000", error.sqlState());
        verifyNoInteractions(glue, backend);
        verify(registry, never()).bind(any());
    }

    @Test
    void preparesDistinctExternalReferences() {
        assertFalse(preparation.prepare("SELECT a.id, SUM(b.amount) FROM lake.sales a JOIN lake.sales b "
                + "ON a.id=b.id JOIN public.customers c ON c.id=a.id GROUP BY a.id ORDER BY a.id", SESSION, backend));
        verify(materializer).ensureCurrent(backend, SESSION, BINDING, "sales");
    }

    @Test
    void leavesInternalSqlUntouched() {
        assertFalse(preparation.prepare("SELECT 'lake.sales' FROM public.customers lake", SESSION, backend));
        verifyNoInteractions(materializer, backend);
    }

    @Test
    void rejectsExternalMutationInCte() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "WITH changed AS (DELETE FROM lake.sales RETURNING *) SELECT * FROM changed", SESSION, backend));
        assertEquals("0A000", error.sqlState());
        verifyNoInteractions(materializer, backend);
    }

    @Test
    void missingGlueTableDoesNotUseCachedRelation() {
        when(materializer.ensureCurrent(backend, SESSION, BINDING, "sales"))
                .thenReturn(ExternalTableMaterializer.Outcome.NOT_EXTERNAL);
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () ->
                preparation.prepare("SELECT * FROM lake.sales", SESSION, backend));
        assertEquals("42P01", error.sqlState());
    }

    @Test
    void failedSchemaCreateDoesNotPublishBinding() {
        when(registry.find(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        doThrow(new SpectrumReadException("58030", "backend unavailable")).when(backend).execute(anyString());
        assertThrows(SpectrumReadException.class, () -> preparation.prepare(
                "CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE 'analytics' IAM_ROLE '"
                        + BINDING.iamRoleArn() + "'", SESSION, backend));
        verify(registry, never()).bind(any());
    }

    private static final String CREATE_LAKE = "CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE 'analytics' IAM_ROLE '"
            + BINDING.iamRoleArn() + "'";

    @Test
    void schemaCreatedInClientTransactionIsBoundOnlyWhenItCommits() {
        when(registry.find(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(backend.permitsCachePublication()).thenReturn(false);
        assertTrue(preparation.prepare(CREATE_LAKE, SESSION, backend));
        verify(registry, never()).bind(any());
        preparation.finishCycle(backend, true);
        verify(registry).bind(any());
    }

    @Test
    void schemaCreatedInRolledBackTransactionIsNeverBound() {
        when(registry.find(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(backend.permitsCachePublication()).thenReturn(false);
        assertTrue(preparation.prepare(CREATE_LAKE, SESSION, backend));
        preparation.finishCycle(backend);
        preparation.finishCycle(backend, true);
        verify(registry, never()).bind(any());
    }

    private static final SpectrumSession TRANSACTION = new SpectrumSession(SESSION.accountId(), SESSION.clusterKey(),
            "dev", List.of(), true);
    private static final String CREATE_SALES =
            "CREATE EXTERNAL TABLE lake.sales (id INTEGER) STORED AS TEXTFILE LOCATION 's3://bucket/'";

    @Test
    void transactionalTableCreateIsRejectedBeforeChangingEitherStore() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE_SALES, TRANSACTION, backend));
        assertEquals("25001", error.sqlState());
        assertEquals("CREATE EXTERNAL TABLE cannot run inside a transaction block", error.getMessage());
        verifyNoInteractions(glue, backend);
    }

    @Test
    void transactionalTableCreateInUnboundSchemaIsRejectedAndRoutedToPreparation() {
        String legacy = "CREATE EXTERNAL TABLE legacy.sales (id INTEGER) STORED AS TEXTFILE LOCATION 's3://bucket/'";
        assertTrue(preparation.handlesDdl(legacy, TRANSACTION));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(legacy, TRANSACTION, backend));
        assertEquals("25001", error.sqlState());
        verifyNoInteractions(glue, backend);
    }

    @Test
    void tableCreatedOutsideTransactionIsStoredInGlue() {
        assertTrue(preparation.prepare(CREATE_SALES, SESSION, backend));
        verify(glue).createTable(eq("analytics"), any());
    }

    @Test
    void discardingLoadsOnlyDropsPendingLoads() {
        preparation.discardPendingLoads(backend);
        verify(materializer).finishCycle(backend);
        verifyNoInteractions(glue);
    }

    @Test
    void schemaCreatedOnAutocommitBackendIsBoundImmediately() {
        when(registry.find(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(backend.permitsCachePublication()).thenReturn(true);
        assertTrue(preparation.prepare(CREATE_LAKE, SESSION, backend));
        verify(registry).bind(any());
    }
}
