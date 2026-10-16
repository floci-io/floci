package io.github.hectorvent.floci.services.rds.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.ExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export of one snapshot with Docker, DuckDB and S3 replaced: where rows and Parquet are staged,
 * what reaches the export's bucket, and that the restore container and its volume are removed
 * however the export ends.
 */
class PostgresSnapshotExporterTest {

    private static final String STAGING = "floci-rds-export-staging";
    private static final Instant FINISHED = Instant.parse("2026-10-13T09:05:00Z");

    private RdsContainerManager containerManager;
    private FlociDuckClient duckClient;
    private S3Service s3Service;
    private PostgresSnapshotExporter exporter;
    private final SnapshotExportSource source = new SnapshotExportSource(
            "arn:aws:rds:us-east-1:000000000000:snapshot:snap", "postgres:16-alpine", "master", "secret",
            Instant.parse("2026-10-13T08:00:00Z"), "-- dump");
    private final SnapshotExporter.Progress progress = new SnapshotExporter.Progress() {
        @Override
        public void extracting(int percent) {
        }

        @Override
        public boolean canceled() {
            return false;
        }

        @Override
        public boolean abandonedByReset() {
            return false;
        }
    };

    @BeforeEach
    void setUp() {
        containerManager = mock(RdsContainerManager.class);
        duckClient = mock(FlociDuckClient.class);
        s3Service = mock(S3Service.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().rds().exportStagingBucket()).thenReturn(STAGING);
        exporter = new PostgresSnapshotExporter(containerManager, duckClient, s3Service, new ObjectMapper(),
                Clock.fixed(FINISHED, ZoneOffset.UTC), config);

        when(containerManager.startForExport(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restore-container", "appdb-export", "localhost", 5432));
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("postgres"), anyString()))
                .thenReturn("\"appdb\"\n");
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.contains("pg_attribute"))))
                .thenReturn("{\"schema\":\"public\",\"table\":\"items\",\"column\":\"id\",\"type\":\"int4\"}\n");
        when(s3Service.bucketExists(STAGING)).thenReturn(true);
        when(s3Service.getObject(eq(STAGING), anyString()))
                .thenReturn(new S3Object(STAGING, "data.gz.parquet", new byte[]{'P', 'A', 'R', '1'}, "application/octet-stream"));
    }

    private ExportTask task() {
        ExportTask task = new ExportTask();
        task.setExportTaskIdentifier("appdb-export");
        task.setRuntimeArn("arn:aws:rds:us-east-1:000000000000:export-task:appdb-export");
        task.setRuntimeVolumeId("export-0123456789abcdef");
        task.setS3Bucket("exports");
        task.setS3Prefix("search/rds");
        task.setRegion("us-east-1");
        task.setAccountId("000000000000");
        return task;
    }

    private void tableRows(String rows) {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(4);
            out.write(rows.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(containerManager).queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.startsWith("SELECT row_to_json")), any(OutputStream.class),
                eq(PostgresSnapshotExporter.TABLE_READ_TIMEOUT_SECONDS));
    }

    @Test
    void rowsAndParquetAreStagedOutsideTheExportBucket() {
        tableRows("{\"id\":1}\n{\"id\":2}\n");

        SnapshotExporter.Outcome outcome = exporter.export(task(), source, progress);

        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        verify(duckClient).execute(eq("SELECT 1 AS ok"), setupSql.capture(), isNull(), eq("000000000000"));
        assertThat(setupSql.getValue(), containsString(
                "read_json('s3://" + STAGING + "/rds-export/export-0123456789abcdef/1/part-*.ndjson'"));
        assertThat(setupSql.getValue(), containsString(
                "TO 's3://" + STAGING + "/rds-export/export-0123456789abcdef/1/data.gz.parquet'"));
        verify(s3Service).putObject(eq(STAGING), eq("rds-export/export-0123456789abcdef/1/part-0.ndjson"),
                argThat(bytes -> new String(bytes, StandardCharsets.UTF_8).equals("{\"id\":1}\n{\"id\":2}\n")),
                anyString(), any());

        ArgumentCaptor<String> exportKeys = ArgumentCaptor.forClass(String.class);
        verify(s3Service, atLeastOnce()).putObject(eq("exports"), exportKeys.capture(), any(), anyString(), any());
        assertThat(exportKeys.getAllValues(), everyItem(startsWith("search/rds/appdb-export/")));
        assertThat(exportKeys.getAllValues(), everyItem(not(containsString("ndjson"))));
        assertThat(exportKeys.getAllValues().get(0),
                matchesPattern("search/rds/appdb-export/appdb/public\\.items/1/part-00000-[0-9a-f-]{36}\\.gz\\.parquet"));
        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/part-0.ndjson");
        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/data.gz.parquet");
        assertThat(outcome.finishedAt(), equalTo(FINISHED));
        assertThat(outcome.bytesExtracted(), equalTo(18L));
    }

    @Test
    void exportInfoRecordsTheClocksEndTime() throws Exception {
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("exports"), eq("search/rds/appdb-export/export_info_appdb-export.json"),
                body.capture(), anyString(), any());
        JsonNode info = new ObjectMapper().readTree(body.getValue());
        assertThat(info.get("taskEndTime").asText(), equalTo("2026-10-13T09:05:00Z"));
    }

    @Test
    void aFailedConversionLeavesNothingInTheExportBucket() {
        tableRows("{\"id\":1}\n");
        doThrow(new RuntimeException("floci-duck execute returned HTTP 500")).when(duckClient)
                .execute(anyString(), anyString(), isNull(), anyString());

        assertThrows(RuntimeException.class, () -> exporter.export(task(), source, progress));

        verify(s3Service, never()).putObject(eq("exports"), anyString(), any(), anyString(), any());
        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/part-0.ndjson");
        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/data.gz.parquet");
        verify(containerManager).discardExport("export-0123456789abcdef");
    }

    @Test
    void aTableFinishedAfterAResetIsNotWrittenToTheExportBucket() {
        tableRows("{\"id\":1}\n");
        AtomicBoolean reset = new AtomicBoolean();
        doAnswer(invocation -> {
            reset.set(true);
            return null;
        }).when(duckClient).execute(anyString(), anyString(), isNull(), anyString());
        SnapshotExporter.Progress resetMidTable = new SnapshotExporter.Progress() {
            @Override
            public void extracting(int percent) {
            }

            @Override
            public boolean canceled() {
                return reset.get();
            }

            @Override
            public boolean abandonedByReset() {
                return reset.get();
            }
        };

        exporter.export(task(), source, resetMidTable);

        verify(s3Service, never()).putObject(eq("exports"), anyString(), any(), anyString(), any());
        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/data.gz.parquet");
    }

    @Test
    void aChunkWhoseUploadFailsIsStillRemovedFromStaging() {
        tableRows("{\"id\":1}\n");
        doThrow(new IllegalStateException("staging bucket unavailable")).when(s3Service)
                .putObject(eq(STAGING), eq("rds-export/export-0123456789abcdef/1/part-0.ndjson"), any(), anyString(),
                        any());

        assertThrows(IllegalStateException.class, () -> exporter.export(task(), source, progress));

        verify(s3Service).deleteObject(STAGING, "rds-export/export-0123456789abcdef/1/part-0.ndjson");
    }

    @Test
    void theSuccessRecordIsWrittenAfterTheTablesRecord() {
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        InOrder order = inOrder(s3Service);
        order.verify(s3Service).putObject(eq("exports"),
                eq("search/rds/appdb-export/export_tables_info_appdb-export_from_1_to_1.json"), any(), anyString(), any());
        order.verify(s3Service).putObject(eq("exports"), eq("search/rds/appdb-export/export_info_appdb-export.json"),
                any(), anyString(), any());
    }

    @Test
    void duckDbReadsARowLargerThanItsDefaultObjectSize() {
        String largeRow = "{\"id\":\""
                + "x".repeat(PostgresSnapshotExporter.DUCKDB_DEFAULT_MAXIMUM_OBJECT_SIZE) + "\"}";
        tableRows("{\"id\":\"1\"}\n" + largeRow + "\n");

        exporter.export(task(), source, progress);

        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        verify(duckClient).execute(eq("SELECT 1 AS ok"), setupSql.capture(), isNull(), eq("000000000000"));
        assertThat(setupSql.getValue(), containsString("maximum_object_size=" + (largeRow.length() + 1) + ","));
    }

    @Test
    void duckDbKeepsItsDefaultObjectSizeForSmallRows() {
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        verify(duckClient).execute(eq("SELECT 1 AS ok"), setupSql.capture(), isNull(), eq("000000000000"));
        assertThat(setupSql.getValue(), containsString(
                "maximum_object_size=" + PostgresSnapshotExporter.DUCKDB_DEFAULT_MAXIMUM_OBJECT_SIZE + ","));
    }

    @Test
    void anEmptyTableWritesNoFile() {
        tableRows("");

        exporter.export(task(), source, progress);

        verify(duckClient, never()).execute(anyString(), anyString(), any(), anyString());
        verify(s3Service, never()).putObject(eq("exports"),
                argThat(key -> key.endsWith(".parquet")), any(), anyString(), any());
    }

    @Test
    void aTableInASchemaWithASlashIsSkippedWithAWarning() {
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.contains("pg_attribute"))))
                .thenReturn("{\"schema\":\"sales/eu\",\"table\":\"items\",\"column\":\"id\",\"type\":\"int4\"}\n");

        SnapshotExporter.Outcome outcome = exporter.export(task(), source, progress);

        assertThat(outcome.warnings(), contains(containsString("appdb.sales/eu.items was skipped")));
        verify(s3Service, never()).putObject(eq("exports"),
                argThat(key -> key.endsWith(".parquet")), any(), anyString(), any());
    }

    @Test
    void aTableInADatabaseWithASlashIsSkippedWithAWarning() {
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("postgres"), anyString()))
                .thenReturn("\"app/db\"\n");
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("app/db"),
                argThat(sql -> sql.contains("pg_attribute"))))
                .thenReturn("{\"schema\":\"public\",\"table\":\"items\",\"column\":\"id\",\"type\":\"int4\"}\n");

        SnapshotExporter.Outcome outcome = exporter.export(task(), source, progress);

        assertThat(outcome.warnings(), contains(containsString("app/db.public.items was skipped")));
        verify(s3Service, never()).putObject(eq("exports"),
                argThat(key -> key.endsWith(".parquet")), any(), anyString(), any());
    }

    @Test
    void anOrdinaryTableIsReadWithoutTheRowsOfTablesInheritingFromIt() {
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        verify(containerManager).queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.startsWith("SELECT row_to_json") && sql.contains(" FROM ONLY \"public\".\"items\")")),
                any(OutputStream.class), eq(PostgresSnapshotExporter.TABLE_READ_TIMEOUT_SECONDS));
    }

    @Test
    void aPartitionedTableIsReadThroughItsPartitions() {
        when(containerManager.queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.contains("pg_attribute"))))
                .thenReturn("{\"schema\":\"public\",\"table\":\"items\",\"kind\":\"p\",\"column\":\"id\","
                        + "\"type\":\"int4\"}\n");
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        verify(containerManager).queryPostgres(eq("restore-container"), eq("master"), eq("appdb"),
                argThat(sql -> sql.startsWith("SELECT row_to_json") && sql.contains(" FROM \"public\".\"items\")")),
                any(OutputStream.class), eq(PostgresSnapshotExporter.TABLE_READ_TIMEOUT_SECONDS));
    }

    @Test
    void theVolumeIsRemovedAndTheRuntimeReleasedWhenStoppingTheContainerFails() {
        tableRows("{\"id\":1}\n");
        doThrow(new IllegalStateException("Docker refused to stop the container"))
                .when(containerManager).stop(any());

        exporter.export(task(), source, progress);

        InOrder order = inOrder(containerManager);
        order.verify(containerManager).discardExport("export-0123456789abcdef");
        order.verify(containerManager).stopByRuntimeId("arn:aws:rds:us-east-1:000000000000:export-task:appdb-export");
    }

    @Test
    void theRuntimeIsStillReleasedWhenRemovingTheVolumeFailsAfterAFailedStop() {
        tableRows("{\"id\":1}\n");
        doThrow(new IllegalStateException("Docker refused to stop the container"))
                .when(containerManager).stop(any());
        doThrow(new IllegalStateException("Docker refused to remove the volume"))
                .when(containerManager).discardExport(any());

        exporter.export(task(), source, progress);

        verify(containerManager).stopByRuntimeId("arn:aws:rds:us-east-1:000000000000:export-task:appdb-export");
    }

    @Test
    void theVolumeIsRemovedAndTheRuntimeReleasedWhenTheContainerNeverStarted() {
        when(containerManager.startForExport(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("image not found"));

        assertThrows(IllegalStateException.class, () -> exporter.export(task(), source, progress));

        verify(containerManager, never()).stop(any());
        InOrder order = inOrder(containerManager);
        order.verify(containerManager).discardExport("export-0123456789abcdef");
        order.verify(containerManager).stopByRuntimeId("arn:aws:rds:us-east-1:000000000000:export-task:appdb-export");
    }

    @Test
    void theRestoreContainerTakesTheTasksRuntimeArnAndVolume() {
        tableRows("");

        exporter.export(task(), source, progress);

        verify(containerManager).startForExport("arn:aws:rds:us-east-1:000000000000:export-task:appdb-export",
                "appdb-export", "export-0123456789abcdef", "postgres:16-alpine", "master", "secret");
    }

    @Test
    void discardRemovesWhatARestartLeftBehind() {
        exporter.discard(task());

        verify(containerManager).discardExport("export-0123456789abcdef");
    }

    @Test
    void aStagingBucketIsCreatedWhenMissing() {
        when(s3Service.bucketExists(STAGING)).thenReturn(false);
        tableRows("{\"id\":1}\n");

        exporter.export(task(), source, progress);

        verify(s3Service).createBucket(STAGING, "us-east-1");
    }

    @Test
    void theDumpIsRestoredBeforeAnythingIsRead() {
        tableRows("");

        exporter.export(task(), source, progress);

        verify(containerManager).restorePostgresSnapshot("restore-container", "master", "-- dump");
    }
}
