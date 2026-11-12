package io.floci.aws.services.athena;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.athena.model.ResultConfiguration;
import io.floci.aws.services.athena.model.ResultSet;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code StopQueryExecution} must stay terminal. The async worker used to overwrite whatever
 * {@code stopQueryExecution} wrote, so a stopped query later reported {@code SUCCEEDED} or
 * {@code FAILED}. These tests drive the worker with a stubbed duck client.
 */
class AthenaServiceTest {

    private Vertx vertx;
    private FlociDuckClient duckClient;
    private S3Service s3Service;
    private AthenaService service;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        duckClient = mock(FlociDuckClient.class);

        StorageFactory storageFactory = mock(StorageFactory.class);
        doAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"))
                .when(storageFactory).create(anyString(), anyString(), any());

        GlueService glueService = mock(GlueService.class);
        when(glueService.getTables(anyString())).thenReturn(List.of());
        s3Service = mock(S3Service.class);
        GlueViewDdlBuilder ddlBuilder = mock(GlueViewDdlBuilder.class);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.athena()).thenReturn(athena);
        when(athena.mock()).thenReturn(false);

        service = new AthenaService(storageFactory, duckClient, glueService, s3Service,
                config, vertx, ddlBuilder, mock(RegionResolver.class));
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void stopDuringExecutionKeepsCancellationTerminal() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            finished.countDown();
            return null;
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(id);
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState());

        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState(),
                "the worker must not overwrite a cancellation");
    }

    @Test
    void stopDuringExecutionStaysTerminalWhenTheWorkerFails() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            finished.countDown();
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(id);
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState(),
                "a worker failure must not overwrite a cancellation");
    }

    @Test
    void stopAfterCompletionLeavesTheExecutionSucceeded() throws Exception {
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.SUCCEEDED);
        service.stopQueryExecution(id);

        assertEquals(QueryExecutionState.SUCCEEDED, service.getQueryExecution(id).getStatus().getState(),
                "a late stop must not cancel a finished query");
    }

    @Test
    void stopAfterFailureLeavesTheExecutionFailed() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.FAILED);
        service.stopQueryExecution(id);

        assertEquals(QueryExecutionState.FAILED, service.getQueryExecution(id).getStatus().getState(),
                "a late stop must not cancel a failed query");
    }

    @Test
    void getQueryResultsReportsTheSqlTypeOfEachColumn() throws Exception {
        String query = "SELECT CAST(1 AS BIGINT) AS n, CAST(1.5 AS DOUBLE) AS d, true AS b, "
                + "DATE '2026-01-01' AS dt, CAST(2.50 AS DECIMAL(10,2)) AS p, 'x' AS s";
        when(duckClient.query(eq("DESCRIBE " + query), any())).thenReturn(List.of(
                Map.of("column_name", "n", "column_type", "BIGINT"),
                Map.of("column_name", "d", "column_type", "DOUBLE"),
                Map.of("column_name", "b", "column_type", "BOOLEAN"),
                Map.of("column_name", "dt", "column_type", "DATE"),
                Map.of("column_name", "p", "column_type", "DECIMAL(10,2)"),
                Map.of("column_name", "s", "column_type", "VARCHAR")));

        assertEquals(List.of("bigint", "double", "boolean", "date", "decimal", "varchar"),
                columnInfo(query, "n,d,b,dt,p,s\n1,1.5,true,2026-01-01,2.50,x\n").stream()
                        .map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void getQueryResultsReportsThePrecisionAndScaleOfADecimalColumn() throws Exception {
        String query = "SELECT CAST(2.50 AS DECIMAL(10,2)) AS p";
        when(duckClient.query(eq("DESCRIBE " + query), any())).thenReturn(List.of(
                Map.of("column_name", "p", "column_type", "DECIMAL(10,2)")));

        ResultSet.ColumnInfo column = columnInfo(query, "p\n2.50\n").get(0);

        assertEquals("decimal", column.getType());
        assertEquals(10, column.getPrecision());
        assertEquals(2, column.getScale());
    }

    @Test
    void getQueryResultsReportsTimeAndTimestampVariantsByTheirAthenaNames() throws Exception {
        String query = "SELECT t, ts_s, ts_ms, ts_ns, ts_tz, t_tz FROM times";
        when(duckClient.query(eq("DESCRIBE " + query), any())).thenReturn(List.of(
                Map.of("column_name", "t", "column_type", "TIME"),
                Map.of("column_name", "ts_s", "column_type", "TIMESTAMP_S"),
                Map.of("column_name", "ts_ms", "column_type", "TIMESTAMP_MS"),
                Map.of("column_name", "ts_ns", "column_type", "TIMESTAMP_NS"),
                Map.of("column_name", "ts_tz", "column_type", "TIMESTAMP WITH TIME ZONE"),
                Map.of("column_name", "t_tz", "column_type", "TIME WITH TIME ZONE")));

        assertEquals(List.of("time", "timestamp", "timestamp", "timestamp", "timestamp with time zone",
                        "time with time zone"),
                columnInfo(query, "t,ts_s,ts_ms,ts_ns,ts_tz,t_tz\n,,,,,\n").stream()
                        .map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void getQueryResultsReportsVarcharWhenTheTypesCannotBeDescribed() throws Exception {
        when(duckClient.query(anyString(), any())).thenThrow(new IllegalStateException("not describable"));

        assertEquals(List.of("varchar", "varchar"), columnInfo("SELECT 1 AS a, 2 AS b", "a,b\n1,2\n").stream()
                .map(ResultSet.ColumnInfo::getType).toList());
    }

    private List<ResultSet.ColumnInfo> columnInfo(String query, String csv) throws Exception {
        String id = service.startQueryExecution(query, "primary", null,
                new ResultConfiguration("s3://athena-results/"));
        awaitState(id, QueryExecutionState.SUCCEEDED);
        String outputLocation = service.getQueryExecution(id).getResultConfiguration().getOutputLocation();
        String key = outputLocation.substring("s3://athena-results/".length());
        S3Object result = new S3Object("athena-results", key, csv.getBytes(StandardCharsets.UTF_8), "text/csv");
        when(s3Service.listObjects(eq("athena-results"), anyString(), any(), anyInt())).thenReturn(List.of(result));
        when(s3Service.getObject("athena-results", key)).thenReturn(result);

        return service.getQueryResults(id).getMetadata().getColumnInfo();
    }

    private void awaitState(String id, QueryExecutionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (service.getQueryExecution(id).getStatus().getState() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, service.getQueryExecution(id).getStatus().getState());
    }
}
