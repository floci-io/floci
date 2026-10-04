package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalTableMaterializerTest {
    private static final String ACCOUNT = "000000000000";
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(ACCOUNT, ACCOUNT + ":c",
            "dev", "analytics", "lake", "arn:aws:iam::000000000000:role/R");
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private FlociDuckClient duck;
    private GlueService glue;
    private S3Service s3;
    private IamService iam;
    private RecordingBackend backend;
    private ExternalTableMaterializer materializer;

    private static final class RecordingBackend implements BackendSql {
        private final List<String> statements = new ArrayList<>();

        @Override
        public void execute(String sql) {
            statements.add(sql);
        }

        @Override
        public long copyIn(String copySql, InputStream data) {
            statements.add(copySql);
            return 2;
        }
    }

    @BeforeEach
    void setUp() {
        duck = mock(FlociDuckClient.class);
        glue = mock(GlueService.class);
        s3 = mock(S3Service.class);
        iam = mock(IamService.class);
        IamRole role = mock(IamRole.class);
        when(role.getAssumeRolePolicyDocument()).thenReturn(TRUST_POLICY);
        when(iam.findRole(ACCOUNT, "R")).thenReturn(Optional.of(role));
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().spectrumMaxRows()).thenReturn(1000L);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(duck.query(anyString(), any(), anyString())).thenReturn(List.of());
        backend = new RecordingBackend();
        materializer = new ExternalTableMaterializer(duck, glue, s3, iam, config);
        when(s3.openObjectStream(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString(), isNull()))
                .thenReturn(new ByteArrayInputStream("id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8)));
        S3Object icebergMetadata = new S3Object("bucket", "events/metadata/v1.json",
                "{\"current-snapshot-id\":\"1\",\"snapshots\":[{\"snapshot-id\":\"1\","
                        .concat("\"manifest-list\":\"s3://bucket/events/metadata/snap*.avro\"}]}")
                        .getBytes(StandardCharsets.UTF_8), "application/json");
        when(s3.getObject(eq("bucket"), argThat(key -> key.startsWith("events/metadata/"))))
                .thenReturn(icebergMetadata);
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), false, null));
        when(s3.isAuthEnforced()).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq(ExternalTableMaterializer.SCRATCH_BUCKET), eq("spectrum-"), eq(""),
                eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
    }

    @Test
    void loadsThroughAStagingTableAndCachesUnchangedData() {
        Table table = csvTable();
        when(glue.getTable("lake", "events")).thenReturn(table);

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(backend.statements.get(0), containsString("CREATE TEMP TABLE \"floci_spectrum_stg_"));
        assertThat(backend.statements.get(0), containsString("\"id\" integer, \"name\" text"));
        assertThat(backend.statements.get(1), containsString("COPY pg_temp.\"floci_spectrum_stg_"));
        assertThat(backend.statements.get(2), containsString("CREATE TABLE \"analytics\".\"events\""));
        assertThat(backend.statements.get(2), containsString("FROM pg_temp.\"floci_spectrum_stg_"));
        assertThat(backend.statements.stream().noneMatch(sql -> sql.contains("events__stg")), equalTo(true));
        verify(s3).openObjectStream(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString(), isNull());
        verify(s3, never()).getObject(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString());
        verify(duck).execute(argThat(sql -> sql.contains("LIMIT 1001")), isNull(), anyString(), eq(ACCOUNT));
        int size = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        assertThat(backend.statements.size(), equalTo(size));
    }

    @Test
    void readsOnlyTheExactS3ObjectsThatWereListedAndAuthorized() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        new S3Object("bucket", "events/part*'one'.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), false, null));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("read_csv_auto(['s3://bucket/events/part[*]''one''.csv'])")
                && !sql.contains("/**")), isNull(), anyString(), eq(ACCOUNT));
    }

    @Test
    void deniesIcebergDataFilesReferencedByManifestBeforeScanningThem() {
        Table table = icebergTable("s3://bucket/events/metadata/v[1].json");
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.isAuthEnforced()).thenReturn(true);
        when(duck.query(anyString(), any(), eq(ACCOUNT))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.contains("status IN ('ADDED', 'EXISTING')") || sql.contains("content = 'DATA'")) {
                return List.of();
            }
            return List.of(
                    Map.of("file_path", "s3://bucket/events/data.parquet", "manifest_content", "DATA",
                            "content", "EXISTING", "status", "ADDED"),
                    Map.of("file_path", "s3://bucket/private/deletes.parquet", "manifest_content", "DELETES",
                            "content", "POSITION_DELETES", "status", "ADDED"));
        });
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"arn:aws:s3:::bucket/private/deletes.parquet"}
                ]}""")));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck).query(argThat(sql -> sql.contains("iceberg_metadata('s3://bucket/events/metadata/v[[]1].json')")),
                any(), eq(ACCOUNT));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void deniesIcebergManifestBeforeTheMetadataScanReadsIt() {
        Table table = icebergTable("s3://bucket/events/metadata/v1.json");
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.isAuthEnforced()).thenReturn(true);
        when(duck.query(anyString(), any(), eq(ACCOUNT))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("read_avro")) {
                return List.of(Map.of("manifest_path", "s3://bucket/private/manifest.avro"));
            }
            return List.of();
        });
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"arn:aws:s3:::bucket/private/manifest.avro"}
                ]}""")));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck).query(argThat(sql -> sql.contains("read_avro('s3://bucket/events/metadata/snap[*].avro')")),
                any(), eq(ACCOUNT));
        verify(duck, never()).query(argThat(sql -> sql.contains("iceberg_metadata")), any(), eq(ACCOUNT));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void emptyS3LocationCreatesAnEmptyTableWithoutCallingDuckDb() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
        assertThat(backend.statements.size(), equalTo(2));
    }

    @Test
    void doesNotCacheLoadsThatMayBeRolledBack() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(true), BINDING, "events");
        assertThat(materializer.ensureCurrent(backend, session(true), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void forgettingOneClusterInvalidatesOnlyItsFingerprints() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        SpectrumSession first = session(ACCOUNT + ":first", false);
        SpectrumSession second = session(ACCOUNT + ":second", false);
        ExternalSchemaBinding firstBinding = new ExternalSchemaBinding(ACCOUNT, first.clusterKey(), "dev",
                "analytics", "lake", BINDING.iamRoleArn());
        ExternalSchemaBinding secondBinding = new ExternalSchemaBinding(ACCOUNT, second.clusterKey(), "dev",
                "analytics", "lake", BINDING.iamRoleArn());

        materializer.ensureCurrent(backend, first, firstBinding, "events");
        materializer.ensureCurrent(backend, second, secondBinding, "events");
        int sizeBeforeForget = backend.statements.size();
        materializer.forgetCluster(first.clusterKey());

        assertThat(materializer.ensureCurrent(backend, first, firstBinding, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(materializer.ensureCurrent(backend, second, secondBinding, "events"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        assertThat(backend.statements.size(), equalTo(sizeBeforeForget + 3));
    }

    @Test
    void missingGlueTableIsNotTreatedAsAnExternalTable() {
        when(glue.getTable("lake", "native")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "native"),
                equalTo(ExternalTableMaterializer.Outcome.NOT_EXTERNAL));
        assertThat(backend.statements.size(), equalTo(0));
    }

    @Test
    void deniesAListedObjectUsingItsExactKeyBeforeDuckDbReadsIt() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        S3Object publicObject = new S3Object("bucket", "events/public.csv", new byte[]{1}, "text/csv", "etag1");
        S3Object privateObject = new S3Object("bucket", "events/private.csv", new byte[]{2}, "text/csv", "etag2");
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(publicObject, privateObject), List.of(), false, null));
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"arn:aws:s3:::bucket/events/private.csv"}
                ]}""")));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void doesNotReuseAStagingTableCacheEntryAcrossRedshiftDatabases() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        SpectrumSession otherDatabase = new SpectrumSession(ACCOUNT, BINDING.clusterKey(), "reporting",
                List.of(BINDING.iamRoleArn()), false);
        ExternalSchemaBinding otherBinding = new ExternalSchemaBinding(ACCOUNT, BINDING.clusterKey(),
                "reporting", "analytics", "lake", BINDING.iamRoleArn());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        int statementCount = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, otherDatabase, otherBinding, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(backend.statements.size(), equalTo(statementCount + 3));
    }

    @Test
    void partitionedTableWithoutRegisteredPartitionsLoadsNoRows() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        when(glue.getTable("lake", "events")).thenReturn(table);

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void readsObjectsFromRegisteredPartitionLocationsOutsideTheTableRoot() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        StorageDescriptor partitionDescriptor = new StorageDescriptor();
        partitionDescriptor.setLocation("s3://bucket/archived/day=2026-09-25/");
        partitionDescriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        partitionDescriptor.setColumns(table.getStorageDescriptor().getColumns());
        Partition partition = new Partition();
        partition.setValues(List.of("2026-09-25"));
        partition.setStorageDescriptor(partitionDescriptor);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("archived/day=2026-09-25/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        new S3Object("bucket", "archived/day=2026-09-25/part.csv", new byte[]{1}, "text/csv", "partition-etag")),
                        List.of(), false, null));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/archived/day=2026-09-25/part.csv")
                && sql.contains("CAST('2026-09-25' AS VARCHAR)") && sql.contains("AS \"day\"")),
                any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void reloadRefillsTheExistingTableInPlaceSoDependentViewsSurvive() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1, 2}, "text/csv", "etag2")),
                        List.of(), false, null));
        backend.statements.clear();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        String swap = backend.statements.getLast();
        assertThat(swap, containsString("TRUNCATE \"analytics\".\"events\""));
        assertThat(swap, containsString("INSERT INTO \"analytics\".\"events\" SELECT * FROM pg_temp.\"floci_spectrum_stg_"));
        assertThat(swap.contains("RENAME TO"), equalTo(false));
        assertThat(swap.contains("DROP TABLE IF EXISTS \"analytics\".\"events\";"), equalTo(false));
    }

    @Test
    void reloadRunsTheInPlaceStepsInOrderAndDropsTheStagingTable() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        forceReload();
        backend.statements.clear();

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        String swap = backend.statements.getLast();
        int create = swap.indexOf("CREATE TABLE IF NOT EXISTS \"analytics\".\"events\"");
        int truncate = swap.indexOf("TRUNCATE");
        int insert = swap.indexOf("INSERT INTO");
        int dropStaging = swap.indexOf("DROP TABLE pg_temp.\"floci_spectrum_stg_");
        assertThat(create >= 0 && create < truncate && truncate < insert && insert < dropStaging, equalTo(true));
    }

    @Test
    void aLoadInsideATransactionThatKeepsTheColumnsStillRefillsInPlaceAfterwards() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        materializer.ensureCurrent(backend, session(true), BINDING, "events");
        backend.statements.clear();

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        assertThat(backend.statements.getLast(), containsString("CREATE TABLE IF NOT EXISTS \"analytics\".\"events\""));
        assertThat(backend.statements.getLast(), not(containsString("DROP TABLE IF EXISTS \"analytics\"")));
    }

    @Test
    void aTransactionalLoadThatReplacesTheTableIsNotTrustedAsItsCommittedColumns() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        Table changed = csvTable();
        changed.setVersionId("2");
        changed.getStorageDescriptor().setColumns(List.of(new Column("id", "bigint")));
        when(glue.getTable("lake", "events")).thenReturn(changed);
        materializer.ensureCurrent(backend, session(true), BINDING, "events");
        backend.statements.clear();

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        assertThat(backend.statements.getLast(), containsString("CREATE TABLE \"analytics\".\"events\""));
    }

    @Test
    void forgettingATableDropsItsSchemaSignatureSoItIsRecreated() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        materializer.forget(BINDING.clusterKey(), "dev", "analytics", "events");
        backend.statements.clear();

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        assertThat(backend.statements.getLast(), containsString("CREATE TABLE \"analytics\".\"events\""));
    }

    private void forceReload() {
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{9}, "text/csv", "etag-changed")),
                        List.of(), false, null));
    }

    @Test
    void schemaChangeReplacesTheTableInsteadOfRefillingIt() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        Table changed = csvTable();
        changed.setVersionId("2");
        changed.getStorageDescriptor().setColumns(List.of(new Column("id", "bigint")));
        when(glue.getTable("lake", "events")).thenReturn(changed);
        backend.statements.clear();

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        assertThat(backend.statements.getLast(), containsString("CREATE TABLE \"analytics\".\"events\""));
    }

    @Test
    void deniesAnIcebergMetadataLocationOutsideTheTableLocation() {
        Table table = icebergTable("s3://other-bucket/events/metadata/v1.metadata.json");
        when(glue.getTable("lake", "events")).thenReturn(table);

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void scansIcebergThroughTheSameGlobEscapedMetadataLiteralThatWasAuthorized() {
        when(glue.getTable("lake", "events")).thenReturn(icebergTable("s3://bucket/events/metadata/v[1].json"));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("iceberg_scan('s3://bucket/events/metadata/v[[]1].json')")),
                any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void deniesAnIcebergTableWhoseSnapshotsHaveNoCurrentSnapshot() {
        when(s3.getObject(eq("bucket"), eq("events/metadata/v2.json"))).thenReturn(new S3Object("bucket",
                "events/metadata/v2.json",
                "{\"current-snapshot-id\":-1,\"snapshots\":[{\"snapshot-id\":\"1\",\"manifest-list\":\"s3://bucket/events/metadata/snap.avro\"}]}"
                        .getBytes(StandardCharsets.UTF_8), "application/json"));
        when(glue.getTable("lake", "events")).thenReturn(icebergTable("s3://bucket/events/metadata/v2.json"));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("XX000"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void ignoresHiddenFilesAndFolderMarkersLikeSpectrum() {
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1"),
                        new S3Object("bucket", "events/_SUCCESS", new byte[0], "text/plain", "etag2"),
                        new S3Object("bucket", "events/.hidden.csv", new byte[]{1}, "text/csv", "etag3"),
                        new S3Object("bucket", "events/#draft.csv", new byte[]{1}, "text/csv", "etag4"),
                        new S3Object("bucket", "events/old.csv~", new byte[]{1}, "text/csv", "etag5"),
                        new S3Object("bucket", "events/nested/", new byte[0], "binary/octet-stream", "etag6")),
                        List.of(), false, null));
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/events/p1.csv")
                && !sql.contains("_SUCCESS") && !sql.contains(".hidden") && !sql.contains("#draft")
                && !sql.contains("old.csv~") && !sql.contains("nested/")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void ignoresFilesUnderAHiddenFolderBelowThePrefix() {
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1"),
                        new S3Object("bucket", "events/_temporary/0/part.csv", new byte[]{1}, "text/csv", "etag2"),
                        new S3Object("bucket", "events/.staging/part.csv", new byte[]{1}, "text/csv", "etag3"),
                        new S3Object("bucket", "events/day=1/part.csv", new byte[]{1}, "text/csv", "etag4")),
                        List.of(), false, null));
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/events/p1.csv")
                && sql.contains("s3://bucket/events/day=1/part.csv")
                && !sql.contains("_temporary") && !sql.contains(".staging")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void reloadsWhenAPartitionChangesItsDelimiterOrColumnsAtTheSameLocationAndFormat() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), false, null));
        Partition partition = partitionAt("org.apache.hadoop.mapred.TextInputFormat", table);
        partition.getStorageDescriptor().setSerdeInfo(serdeWith(Map.of("field.delim", ",")));
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));

        Partition delimiterChanged = partitionAt("org.apache.hadoop.mapred.TextInputFormat", table);
        delimiterChanged.getStorageDescriptor().setSerdeInfo(serdeWith(Map.of("field.delim", "|")));
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(delimiterChanged));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        Partition columnsChanged = partitionAt("org.apache.hadoop.mapred.TextInputFormat", table);
        columnsChanged.getStorageDescriptor().setSerdeInfo(serdeWith(Map.of("field.delim", "|")));
        columnsChanged.getStorageDescriptor().setColumns(List.of(new Column("id", "bigint")));
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(columnsChanged));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    private static StorageDescriptor.SerDeInfo serdeWith(Map<String, String> parameters) {
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serde.setParameters(parameters);
        return serde;
    }

    @Test
    void readsAnIcebergTableOnEveryQueryBecauseItsSnapshotCanChangeUnseen() {
        when(glue.getTable("lake", "events")).thenReturn(icebergTable("s3://bucket/events/metadata/v1.metadata.json"));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void refusesToUseAScratchBucketNameThatBelongsToAUserBucket() {
        when(s3.createBucket(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString()))
                .thenThrow(new AwsException("BucketAlreadyOwnedByYou", "owned", 409));
        when(s3.getBucketTagging(ExternalTableMaterializer.SCRATCH_BUCKET)).thenReturn(Map.of());
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.getMessage(), containsString("not the Spectrum scratch bucket"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
        verify(s3, never()).deleteObject(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString());
    }

    @Test
    void tagsTheScratchBucketItCreatesAndReusesATaggedOne() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        verify(s3).putBucketTagging(ExternalTableMaterializer.SCRATCH_BUCKET, Map.of(
                S3Service.INTERNAL_BUCKET_TAG_KEY, S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE));

        when(s3.createBucket(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString()))
                .thenThrow(new AwsException("BucketAlreadyOwnedByYou", "owned", 409));
        when(s3.getBucketTagging(ExternalTableMaterializer.SCRATCH_BUCKET)).thenReturn(Map.of(
                S3Service.INTERNAL_BUCKET_TAG_KEY, S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE));
        forceReload();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void authorizesTheIcebergMetadataObjectAsTheRole() {
        when(glue.getTable("lake", "events")).thenReturn(icebergTable("s3://bucket/events/metadata/v1.metadata.json"));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(s3).authorizeSignedGetObject(anyString(), anyString(), eq("bucket"), eq("events/metadata/v1.metadata.json"));
    }

    @Test
    void listsWithTheTableLocationPrefixSoPrefixConditionsApply() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::bucket",
                   "Condition":{"StringLike":{"s3:prefix":["events/*"]}}},
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}
                ]}""")));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void deniesListingWhenThePrefixConditionDoesNotMatchTheTableLocation() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::bucket",
                   "Condition":{"StringLike":{"s3:prefix":["other/*"]}}},
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}
                ]}""")));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
    }


    @Test
    void projectingTableWithoutRegisteredPartitionsIgnoresItsLocationTemplate() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("dt", "string")));
        table.setParameters(Map.of("projection.enabled", "true",
                "storage.location.template", "s3://other-bucket/events/${dt}/"));
        when(glue.getTable("lake", "events")).thenReturn(table);

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
        verify(s3, never()).listObjectsWithPrefixes(eq("other-bucket"), anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void sweepsStaleScratchObjectsOnceAndKeepsRecentOnes() {
        S3Object stale = new S3Object(ExternalTableMaterializer.SCRATCH_BUCKET, "spectrum-old.csv",
                new byte[]{1}, "text/csv", "e1");
        stale.setLastModified(Instant.now().minus(Duration.ofHours(3)));
        S3Object recent = new S3Object(ExternalTableMaterializer.SCRATCH_BUCKET, "spectrum-new.csv",
                new byte[]{1}, "text/csv", "e2");
        when(s3.listObjectsWithPrefixes(eq(ExternalTableMaterializer.SCRATCH_BUCKET), eq("spectrum-"), eq(""),
                eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(stale, recent), List.of(), false, null));
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        forceReload();
        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(s3).deleteObject(ExternalTableMaterializer.SCRATCH_BUCKET, "spectrum-old.csv");
        verify(s3, never()).deleteObject(ExternalTableMaterializer.SCRATCH_BUCKET, "spectrum-new.csv");
        verify(s3, times(1)).listObjectsWithPrefixes(eq(ExternalTableMaterializer.SCRATCH_BUCKET),
                eq("spectrum-"), eq(""), eq(1000), any(), any());
    }

    @Test
    void nullsAPartitionValueWiderThanItsVarcharKey() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "varchar(3)")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://bucket/events/");
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        descriptor.setColumns(table.getStorageDescriptor().getColumns());
        Partition partition = new Partition();
        partition.setValues(List.of("2026-09-25"));
        partition.setStorageDescriptor(descriptor);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("CAST(NULL AS VARCHAR)")
                && !sql.contains("2026-09-25") && sql.contains("AS \"day\"")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void reloadsWhenAPartitionChangesFormatAtTheSameLocation() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = partitionAt("org.apache.hadoop.mapred.TextInputFormat", table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), false, null));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));

        when(glue.getPartitions("lake", "events")).thenReturn(List.of(
                partitionAt("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", table)));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void aSessionOutsideATransactionWaitsOutASlowLoadInsteadOfTimingOut() throws Exception {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            loading.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(duck).execute(anyString(), any(), anyString(), anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ExternalTableMaterializer.Outcome> first = executor.submit(
                    () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));
            assertThat(loading.await(10, TimeUnit.SECONDS), equalTo(true));
            Future<ExternalTableMaterializer.Outcome> second = executor.submit(
                    () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS), equalTo(ExternalTableMaterializer.Outcome.LOADED));
            assertThat(second.get(10, TimeUnit.SECONDS), equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private Partition partitionAt(String inputFormat, Table table) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://bucket/events/");
        descriptor.setInputFormat(inputFormat);
        descriptor.setColumns(table.getStorageDescriptor().getColumns());
        Partition partition = new Partition();
        partition.setValues(List.of("2026-09-25"));
        partition.setStorageDescriptor(descriptor);
        return partition;
    }

    @Test
    void listsEveryPageOfTheTableLocation() {
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), isNull(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), true, "page-2"));
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), eq("page-2"), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p2.csv", new byte[]{1}, "text/csv", "etag2")),
                        List.of(), false, null));
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/events/p1.csv")
                && sql.contains("s3://bucket/events/p2.csv")), any(), anyString(), eq(ACCOUNT));
    }

    private Table icebergTable(String metadataLocation) {
        Table table = csvTable();
        table.setParameters(Map.of("table_type", "ICEBERG", "metadata_location", metadataLocation));
        return table;
    }

    private static SpectrumSession session(boolean inTransaction) {
        return session(BINDING.clusterKey(), inTransaction);
    }

    private static SpectrumSession session(String clusterKey, boolean inTransaction) {
        return new SpectrumSession(ACCOUNT, clusterKey, "dev", List.of(BINDING.iamRoleArn()), inTransaction);
    }

    private Table csvTable() {
        Column id = new Column();
        id.setName("id");
        id.setType("int");
        Column name = new Column();
        name.setName("name");
        name.setType("string");
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        descriptor.setLocation("s3://bucket/events/");
        descriptor.setColumns(List.of(id, name));
        Table table = new Table();
        table.setName("events");
        table.setVersionId("1");
        table.setStorageDescriptor(descriptor);
        return table;
    }

    @Test
    void deniesReadsThatOnlyTheBucketPolicyForbids() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedGetObject(anyString(), anyString(), eq("bucket"), eq("events/p1.csv"));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void authorizesListingAndEveryObjectAsTheRoleSessionAndReleasesIt() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(s3).authorizeSignedListBucket(anyString(), anyString(), eq("bucket"));
        verify(s3).authorizeSignedGetObject(anyString(), anyString(), eq("bucket"), eq("events/p1.csv"));
        verify(iam).unregisterSession(eq(ACCOUNT), anyString());
    }

    @Test
    void releasesTheRoleSessionWhenAuthorizationFails() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedListBucket(anyString(), anyString(), eq("bucket"));

        assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        verify(iam).unregisterSession(eq(ACCOUNT), anyString());
    }

    @Test
    void reloadsAfterTheSchemaWasForgottenEvenWhenNothingChanged() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        assertThat(materializer.loadedTables(BINDING.clusterKey(), "dev", "analytics"),
                equalTo(Set.of("events")));

        materializer.forgetSchema(BINDING.clusterKey(), "dev", "analytics");

        assertThat(materializer.loadedTables(BINDING.clusterKey(), "dev", "analytics"), equalTo(Set.of()));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
    }

    @Test
    void readsHeaderlessDelimitedCsvWithTheTablesDeclaredOptions() {
        Table table = csvTable();
        table.setParameters(Map.of("skip.header.line.count", "0"));
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setParameters(Map.of("field.delim", "|"));
        table.getStorageDescriptor().setSerdeInfo(serde);
        when(glue.getTable("lake", "events")).thenReturn(table);

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("read_csv(['s3://bucket/events/p1.csv']")
                && sql.contains("header = false") && sql.contains("delim = '|'")
                && sql.contains("columns = {'id': 'VARCHAR', 'name': 'VARCHAR'}")),
                isNull(), anyString(), eq(ACCOUNT));
    }

    @Test
    void sparsePartitionKeepsTheTablesColumnsAndCsvOptions() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        table.setParameters(Map.of("skip.header.line.count", "0"));
        when(glue.getTable("lake", "events")).thenReturn(table);
        StorageDescriptor sparse = new StorageDescriptor();
        sparse.setLocation("s3://bucket/archived/day=2026-09-25/");
        Partition partition = new Partition();
        partition.setValues(List.of("2026-09-25"));
        partition.setStorageDescriptor(sparse);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("archived/day=2026-09-25/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        new S3Object("bucket", "archived/day=2026-09-25/part.csv", new byte[]{1}, "text/csv", "e")),
                        List.of(), false, null));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("header = false")
                && sql.contains("columns = {'id': 'VARCHAR', 'name': 'VARCHAR'}")
                && sql.contains("CAST('2026-09-25' AS VARCHAR)")), any(), anyString(), eq(ACCOUNT));
    }
}
