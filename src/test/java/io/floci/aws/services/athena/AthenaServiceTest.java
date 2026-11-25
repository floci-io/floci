package io.floci.aws.services.athena;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.CreateWorkGroupRequest;
import io.floci.aws.services.athena.model.QueryExecutionContext;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.athena.model.ResultConfiguration;
import io.floci.aws.services.athena.model.ResultSet;
import io.floci.aws.services.athena.model.WorkGroupTag;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A {@code StopQueryExecution} must stay terminal. The async worker used to overwrite whatever
 * {@code stopQueryExecution} wrote, so a stopped query later reported {@code SUCCEEDED} or
 * {@code FAILED}. These tests drive the worker with a stubbed duck client.
 */
class AthenaServiceTest {

    private static final String REGION = "us-east-1";

    private Vertx vertx;
    private FlociDuckClient duckClient;
    private GlueService glueService;
    private GlueViewDdlBuilder ddlBuilder;
    private AthenaIcebergStatements icebergStatements;
    private S3Service s3Service;
    private AthenaService service;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        duckClient = mock(FlociDuckClient.class);

        StorageFactory storageFactory = mock(StorageFactory.class);
        doAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"))
                .when(storageFactory).create(anyString(), anyString(), any());

        glueService = mock(GlueService.class);
        when(glueService.getTables(anyString())).thenReturn(List.of());
        s3Service = mock(S3Service.class);
        ddlBuilder = mock(GlueViewDdlBuilder.class);
        icebergStatements = mock(AthenaIcebergStatements.class);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.athena()).thenReturn(athena);
        when(athena.mock()).thenReturn(false);

        regionResolver = mock(RegionResolver.class);
        service = new AthenaService(storageFactory, duckClient, glueService, s3Service,
                config, vertx, ddlBuilder, regionResolver, icebergStatements,
                mock(IamEnforcementFilter.class));
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void aQueryExecutionRecordsTheRegionItWasStartedIn() {
        when(regionResolver.getRegion()).thenReturn("eu-west-1");
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        assertTrue(service.findQueryExecution(id).isPresent());
        assertEquals("eu-west-1", service.findQueryExecutionRegion(id).orElseThrow());
        assertTrue(service.findQueryExecutionRegion("missing").isEmpty());
    }

    @Test
    void aQueryExecutionStartedWithNoRegionRecordsNone() {
        when(regionResolver.getRegion()).thenReturn(null);
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        assertTrue(service.findQueryExecution(id).isPresent());
        assertTrue(service.findQueryExecutionRegion(id).isEmpty());
    }

    @Test
    void aQueryExecutionIsAnsweredOnlyInTheRegionItWasStartedIn() {
        when(regionResolver.getRegion()).thenReturn("eu-west-1");
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        assertEquals(id, service.getQueryExecution("eu-west-1", id).getQueryExecutionId());
        String notFound = "QueryExecution " + id + " was not found";
        AwsException read = assertThrows(AwsException.class, () -> service.getQueryExecution("us-east-1", id));
        assertEquals("InvalidRequestException", read.getErrorCode());
        assertEquals(notFound, read.getMessage());
        assertEquals(notFound, assertThrows(AwsException.class,
                () -> service.getQueryResults("us-east-1", id)).getMessage());
        assertEquals(notFound, assertThrows(AwsException.class,
                () -> service.stopQueryExecution("us-east-1", id)).getMessage());
        assertTrue(service.listQueryExecutionsPage("eu-west-1", "primary", null, null).items().stream()
                .anyMatch(execution -> id.equals(execution.getQueryExecutionId())));
        assertTrue(service.listQueryExecutionsPage("us-east-1", "primary", null, null).items().stream()
                .noneMatch(execution -> id.equals(execution.getQueryExecutionId())));
    }

    @Test
    void aQueryExecutionAthenaHoldsNoneOfIsNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getQueryExecution(REGION, "not-a-real-id"));
        assertEquals("InvalidRequestException", missing.getErrorCode());
        assertEquals("QueryExecution not-a-real-id was not found", missing.getMessage());
    }

    @Test
    void aQueryExecutionStoredBeforeItsRegionWasRecordedIsAnsweredInEveryRegion() {
        when(regionResolver.getRegion()).thenReturn(null);
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        assertEquals(id, service.getQueryExecution("us-east-1", id).getQueryExecutionId());
        assertEquals(id, service.getQueryExecution("eu-west-1", id).getQueryExecutionId());
    }

    @Test
    void storedTagsReadTheWorkgroupOrTheDataCatalogTheArnNamesInItsAccountAndRegion() {
        CreateWorkGroupRequest request = new CreateWorkGroupRequest();
        request.setName("analytics");
        request.setTags(List.of(new WorkGroupTag("team", "data")));
        service.createWorkGroup(request, "us-east-1");
        service.createDataCatalog("us-east-1", "lake", "LAMBDA", null, null, List.of(new WorkGroupTag("team", "lake")));
        String arn = "arn:aws:athena:us-east-1:000000000000:";

        assertEquals(Map.of("team", "data"), service.storedTags(arn + "workgroup/analytics"));
        assertEquals(Map.of("team", "lake"), service.storedTags(arn + "datacatalog/lake"));
        assertEquals(Map.of(), service.storedTags(arn + "workgroup/primary"));
        assertEquals(Map.of(), service.storedTags(arn + "datacatalog/AwsDataCatalog"));
        assertEquals(Map.of(), service.storedTags(arn + "workgroup/analytics/session/s-1"));
        assertEquals(Map.of(), service.storedTags("arn:aws:athena:us-east-1:111111111111:workgroup/analytics"));
        assertEquals(Map.of(), service.storedTags("arn:aws:athena:us-west-2:000000000000:workgroup/analytics"));
        assertEquals(Map.of(), service.storedTags("arn:aws-cn:athena:us-east-1:000000000000:workgroup/analytics"));
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
        }).when(duckClient).execute(anyString(), any(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(REGION, id);
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(REGION, id).getStatus().getState());

        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(REGION, id).getStatus().getState(),
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
        }).when(duckClient).execute(anyString(), any(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(REGION, id);
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(REGION, id).getStatus().getState(),
                "a worker failure must not overwrite a cancellation");
    }

    @Test
    void stopAfterCompletionLeavesTheExecutionSucceeded() throws Exception {
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.SUCCEEDED);
        service.stopQueryExecution(REGION, id);

        assertEquals(QueryExecutionState.SUCCEEDED, service.getQueryExecution(REGION, id).getStatus().getState(),
                "a late stop must not cancel a finished query");
    }

    @Test
    void stopAfterFailureLeavesTheExecutionFailed() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.FAILED);
        service.stopQueryExecution(REGION, id);

        assertEquals(QueryExecutionState.FAILED, service.getQueryExecution(REGION, id).getStatus().getState(),
                "a late stop must not cancel a failed query");
    }

    @Test
    void getQueryResultsRefusesAnUnsucceededQueryAsAthenaWordsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(duckClient).execute(anyString(), any(), any(), any());
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        assertEquals("Query has not yet finished. Current state: RUNNING", resultsRefusal(id));
        service.stopQueryExecution(REGION, id);
        release.countDown();
        assertEquals("Could not find results", resultsRefusal(id));

        doAnswer(invocation -> {
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any(), any());
        String failed = service.startQueryExecution("SELECT 1", "primary", null, null);
        awaitState(failed, QueryExecutionState.FAILED);
        assertEquals("Query did not finish successfully. Final query state: FAILED", resultsRefusal(failed));
    }

    private String resultsRefusal(String id) {
        AwsException refused = assertThrows(AwsException.class, () -> service.getQueryResults(REGION, id));
        assertEquals("InvalidRequestException", refused.getErrorCode());
        return refused.getMessage();
    }

    @Test
    void getQueryResultsReportsTheSqlTypeOfEachColumn() throws Exception {
        String query = "SELECT CAST(1 AS BIGINT) AS n, CAST(1.5 AS DOUBLE) AS d, true AS b, "
                + "DATE '2026-01-01' AS dt, CAST(2.50 AS DECIMAL(10,2)) AS p, 'x' AS s";
        when(duckClient.query(eq("DESCRIBE " + query), any(), any())).thenReturn(List.of(
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
        when(duckClient.query(eq("DESCRIBE " + query), any(), any())).thenReturn(List.of(
                Map.of("column_name", "p", "column_type", "DECIMAL(10,2)")));

        ResultSet.ColumnInfo column = columnInfo(query, "p\n2.50\n").get(0);

        assertEquals("decimal", column.getType());
        assertEquals(10, column.getPrecision());
        assertEquals(2, column.getScale());
    }

    @Test
    void getQueryResultsReportsTimeAndTimestampVariantsByTheirAthenaNames() throws Exception {
        String query = "SELECT t, ts_s, ts_ms, ts_ns, ts_tz, t_tz FROM times";
        when(duckClient.query(eq("DESCRIBE " + query), any(), any())).thenReturn(List.of(
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

    /**
     * Athena (engine v3) renders a timestamp column as {@code 2026-03-14 09:30:00.000}; DuckDB writes
     * {@code 2026-03-14 09:30:00}. Every timestamp variant is padded to milliseconds, a longer
     * fraction is kept, and other types, a timestamp with a time zone among them, are left alone.
     */
    @Test
    void getQueryResultsRendersTimestampsWithAtLeastMilliseconds() throws Exception {
        String query = "SELECT ts, ts_ms, ts_us, ts_tz, s FROM times";
        when(duckClient.query(eq("DESCRIBE " + query), any(), any())).thenReturn(List.of(
                Map.of("column_name", "ts", "column_type", "TIMESTAMP"),
                Map.of("column_name", "ts_ms", "column_type", "TIMESTAMP_MS"),
                Map.of("column_name", "ts_us", "column_type", "TIMESTAMP"),
                Map.of("column_name", "ts_tz", "column_type", "TIMESTAMP WITH TIME ZONE"),
                Map.of("column_name", "s", "column_type", "VARCHAR")));

        ResultSet results = results(query, "ts,ts_ms,ts_us,ts_tz,s\n"
                + "2026-03-14 09:30:00,2026-11-12 20:22:54.3,2026-11-12 20:22:54.385678,"
                + "2026-03-14 09:30:00+00,2026-03-14 09:30:00\n"
                + ",infinity,,,\n");

        assertEquals(List.of(
                List.of("ts", "ts_ms", "ts_us", "ts_tz", "s"),
                List.of("2026-03-14 09:30:00.000", "2026-11-12 20:22:54.300", "2026-11-12 20:22:54.385678",
                        "2026-03-14 09:30:00+00", "2026-03-14 09:30:00"),
                List.of("", "infinity", "", "", "")),
                results.getRows().stream()
                        .map(row -> row.getData().stream().map(ResultSet.Datum::getVarCharValue).toList())
                        .toList());
    }

    @Test
    void getQueryResultsReportsVarcharWhenTheTypesCannotBeDescribed() throws Exception {
        when(duckClient.query(anyString(), any(), any())).thenThrow(new IllegalStateException("not describable"));

        assertEquals(List.of("varchar", "varchar"), columnInfo("SELECT 1 AS a, 2 AS b", "a,b\n1,2\n").stream()
                .map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void aQueryHoldingASecondStatementIsRejectedBeforeAnExecutionExists() {
        String twoInserts = "INSERT INTO t VALUES (3); INSERT INTO t VALUES (4)";

        AwsException rejected = assertThrows(AwsException.class,
                () -> service.startQueryExecution(twoInserts, "primary", null, null));

        assertEquals("InvalidRequestException", rejected.getErrorCode());
        assertEquals("Only one sql statement is allowed. Got: " + twoInserts, rejected.getMessage());
        assertEquals(Map.of("AthenaErrorCode", "MALFORMED_QUERY"), rejected.getExtendedData());
        assertEquals(List.of(), service.listQueryExecutions());
        verifyNoInteractions(duckClient);
    }

    @Test
    void aQueryIsRecordedWithoutTheSemicolonsAroundItAndRunsWithItsLastLineEnded() throws Exception {
        String id = service.startQueryExecution("\n;SELECT 1 AS a -- done\n;\n", "primary", null,
                new ResultConfiguration("s3://athena-results/"));
        awaitState(id, QueryExecutionState.SUCCEEDED);

        assertEquals("SELECT 1 AS a -- done", service.getQueryExecution(REGION, id).getQuery());
        verify(duckClient).execute(eq("SELECT 1 AS a -- done\n"), any(), any(), any());
    }

    @Test
    void aQueryRunsWithItsExecutionParametersBoundAndIsRecordedWithItsPlaceholders() throws Exception {
        String id = service.startQueryExecution("SELECT ? AS region, ? AS n", "primary", null,
                new ResultConfiguration("s3://athena-results/"), List.of("'eu'", "10"));
        awaitState(id, QueryExecutionState.SUCCEEDED);

        assertEquals("SELECT ? AS region, ? AS n", service.getQueryExecution(REGION, id).getQuery());
        verify(duckClient).execute(eq("SELECT 'eu' AS region, 10 AS n\n"), any(), any(), any());
    }

    @Test
    void aParameterCountThatDoesNotMatchFailsTheQueryWithAthenasMessage() throws Exception {
        String id = service.startQueryExecution("SELECT ? AS a, ? AS b", "primary", null, null, List.of("1"));
        awaitState(id, QueryExecutionState.FAILED);

        assertEquals("INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 2 but found 1",
                service.getQueryExecution(REGION, id).getStatus().getStateChangeReason());
        verifyNoInteractions(duckClient);
    }

    /**
     * A value naming a column is bound as written (a TODO at {@code usingListEntry}), so the projection checks read
     * the bound statement: {@code tenant = ?} would pass as a static equality, while {@code tenant = (tenant || '')}
     * filters nothing and fails as written in place would. A string holding the column's name is a
     * literal, and passes.
     */
    @Test
    void anInjectedPartitionColumnIsCheckedAgainstTheBoundValue() throws Exception {
        Table events = new Table();
        events.setName("audit_events");
        StorageDescriptor storage = new StorageDescriptor();
        storage.setLocation("s3://lake/audit_events/");
        storage.setColumns(List.of(column("id")));
        events.setStorageDescriptor(storage);
        events.setPartitionKeys(List.of(column("tenant")));
        events.setParameters(Map.of("projection.enabled", "true", "projection.tenant.type", "injected"));
        when(glueService.getTables("analytics")).thenReturn(List.of(events));
        String query = "SELECT id FROM audit_events WHERE tenant = ?";

        String unfiltered = service.startQueryExecution(query, "primary", context("analytics"),
                new ResultConfiguration("s3://athena-results/"), List.of("tenant || ''"));
        awaitState(unfiltered, QueryExecutionState.FAILED);
        assertTrue(service.getQueryExecution(REGION, unfiltered).getStatus().getStateChangeReason()
                .startsWith("CONSTRAINT_VIOLATION: For the injected projected partition column tenant"));
        verifyNoInteractions(duckClient);

        String filtered = service.startQueryExecution(query, "primary", context("analytics"),
                new ResultConfiguration("s3://athena-results/"), List.of("'tenant'"));
        awaitState(filtered, QueryExecutionState.SUCCEEDED);
        verify(duckClient).execute(eq("SELECT id FROM audit_events WHERE tenant = 'tenant'\n"), any(), any(), any());
    }

    /** A value bound as an expression can name a table the query doesn't, and its view is set up all the same. */
    @Test
    void theEngineIsSetUpForTheTablesTheBoundStatementNames() throws Exception {
        String id = service.startQueryExecution("SELECT ? AS n", "primary", context("analytics"),
                new ResultConfiguration("s3://athena-results/"), List.of("(SELECT count(*) FROM events)"));
        awaitState(id, QueryExecutionState.SUCCEEDED);

        String bound = "SELECT ((SELECT count(*) FROM events)) AS n";
        verify(ddlBuilder).build("analytics", bound);
        verify(icebergStatements).s3TablesAttachment(eq(bound), any(), eq("analytics"));
        verify(duckClient).execute(eq(bound + "\n"), any(), any(), any());
    }

    private static QueryExecutionContext context(String database) {
        QueryExecutionContext context = new QueryExecutionContext();
        context.setDatabase(database);
        return context;
    }

    private static Column column(String name) {
        Column column = new Column();
        column.setName(name);
        column.setType("string");
        return column;
    }

    private List<ResultSet.ColumnInfo> columnInfo(String query, String csv) throws Exception {
        return results(query, csv).getMetadata().getColumnInfo();
    }

    private ResultSet results(String query, String csv) throws Exception {
        String id = service.startQueryExecution(query, "primary", null,
                new ResultConfiguration("s3://athena-results/"));
        awaitState(id, QueryExecutionState.SUCCEEDED);
        String outputLocation = service.getQueryExecution(REGION, id).getResultConfiguration().getOutputLocation();
        String key = outputLocation.substring("s3://athena-results/".length());
        S3Object result = new S3Object("athena-results", key, csv.getBytes(StandardCharsets.UTF_8), "text/csv");
        when(s3Service.listObjects(eq("athena-results"), anyString(), any(), anyInt())).thenReturn(List.of(result));
        when(s3Service.getObject("athena-results", key)).thenReturn(result);

        return service.getQueryResults(REGION, id);
    }

    private void awaitState(String id, QueryExecutionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (service.getQueryExecution(REGION, id).getStatus().getState() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, service.getQueryExecution(REGION, id).getStatus().getState());
    }
}
