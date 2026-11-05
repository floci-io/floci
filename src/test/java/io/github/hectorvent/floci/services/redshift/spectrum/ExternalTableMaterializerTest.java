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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalTableMaterializerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String EVENTS_FILE = "s3://bucket/events/p1.parquet";
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
        private boolean publicationAllowed = true;

        @Override
        public boolean permitsCachePublication() {
            return publicationAllowed;
        }

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
        when(role.getArn()).thenReturn("arn:aws:iam::000000000000:role/R");
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
    void confirmedExtendedCommitPublishesDefinitionsAndFingerprint() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        backend.publicationAllowed = false;
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        materializer.finishCycle(backend, true);
        RecordingBackend other = new RecordingBackend();
        assertEquals(ExternalTableMaterializer.Outcome.CURRENT,
                materializer.ensureCurrent(other, session(false), BINDING, "events"));
        forceReload();
        materializer.ensureCurrent(other, session(false), BINDING, "events");
        assertThat(other.statements.getLast(), containsString("CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    void extendedLoadsAreReusableOnlyWithinTheirOwnCycle() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        backend.publicationAllowed = false;
        assertEquals(ExternalTableMaterializer.Outcome.LOADED,
                materializer.ensureCurrent(backend, session(false), BINDING, "events"));
        assertEquals(ExternalTableMaterializer.Outcome.CURRENT,
                materializer.ensureCurrent(backend, session(false), BINDING, "events"));
        RecordingBackend other = new RecordingBackend();
        other.publicationAllowed = false;
        assertEquals(ExternalTableMaterializer.Outcome.LOADED,
                materializer.ensureCurrent(other, session(false), BINDING, "events"));
        materializer.finishCycle(backend);
        assertEquals(ExternalTableMaterializer.Outcome.LOADED,
                materializer.ensureCurrent(backend, session(false), BINDING, "events"));
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
        when(duck.query(anyString(), any(), startsWith("ASIA"), anyString(), anyString())).thenAnswer(invocation -> {
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
                any(), startsWith("ASIA"), anyString(), anyString());
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void coldLoadWithAuthEnforcedTransfersWithTheRoleSessionCredentials() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        verify(duck).execute(argThat(sql -> sql.contains("LIMIT 1001")), isNull(), anyString(),
                startsWith("ASIA"), anyString(), notNull());
        verify(duck, never()).execute(anyString(), any(), anyString(), eq(ACCOUNT));
        verify(iam).registerScratchResource(startsWith("ASIA"),
                startsWith("arn:aws:s3:::floci-internal-redshift-spectrum-scratch/spectrum-"));
        verify(iam).clearScratchResource(startsWith("ASIA"));
    }

    @Test
    void transferDenialDuringColdLoadReturns42501() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));
        doThrow(new RuntimeException("floci-duck execute returned HTTP 500: HTTP Error: HTTP 403 (Forbidden) while writing scratch"))
                .when(duck).execute(anyString(), any(), anyString(), anyString(), anyString(), anyString());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(iam).clearScratchResource(startsWith("ASIA"));
    }

    @Test
    void accessDenialDuringIcebergScanReturns42501() {
        Table table = icebergTable("s3://bucket/events/metadata/v1.json");
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));
        when(duck.query(anyString(), any(), startsWith("ASIA"), anyString(), anyString()))
                .thenThrow(new RuntimeException("floci-duck query error: AccessDenied: Access Denied"));

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
    }

    @Test
    void unrelatedExecutionErrorDuringColdLoadReturns58030() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));
        doThrow(new RuntimeException("floci-duck execute returned HTTP 500: Parser Error: syntax error"))
                .when(duck).execute(anyString(), any(), anyString(), anyString(), anyString(), anyString());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("58030"));
        verify(iam).clearScratchResource(startsWith("ASIA"));
    }

    @Test
    void conversionErrorCarrying403InScratchPathReturns58030() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));
        doThrow(new RuntimeException("floci-duck execute returned HTTP 500: Failed to write to "
                + "'s3://floci-internal-redshift-spectrum-scratch/spectrum-e403d511-1234.csv': "
                + "Conversion Error: Could not convert string 'foo' to INT32"))
                .when(duck).execute(anyString(), any(), anyString(), anyString(), anyString(), anyString());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("58030"));
        verify(iam).clearScratchResource(startsWith("ASIA"));
    }

    @Test
    void conversionErrorOnFailedValueContaining403Returns58030() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));
        doThrow(new RuntimeException("floci-duck execute returned HTTP 500: "
                + "Conversion Error: Could not convert string '403' to INT32"))
                .when(duck).execute(anyString(), any(), anyString(), anyString(), anyString(), anyString());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("58030"));
        verify(iam).clearScratchResource(startsWith("ASIA"));
    }

    @Test
    void deniesIcebergManifestBeforeTheMetadataScanReadsIt() {
        Table table = icebergTable("s3://bucket/events/metadata/v1.json");
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.isAuthEnforced()).thenReturn(true);
        when(duck.query(anyString(), any(), startsWith("ASIA"), anyString(), anyString())).thenAnswer(invocation -> {
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
                any(), startsWith("ASIA"), anyString(), anyString());
        verify(duck, never()).query(argThat(sql -> sql.contains("iceberg_metadata")), any(), anyString(), anyString(), anyString());
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

    @Test
    void invalidatingDataKeepsTheExistingTableAndItsDependencies() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        materializer.invalidateTable(BINDING.clusterKey(), "dev", "analytics", "events");
        backend.statements.clear();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        String swap = backend.statements.getLast();
        assertThat(swap, containsString("TRUNCATE \"analytics\".\"events\""));
        assertThat(swap, not(containsString("DROP TABLE IF EXISTS \"analytics\".\"events\"")));
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
        when(s3.createInternalBucket(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString()))
                .thenThrow(new AwsException("BucketAlreadyOwnedByYou", "owned", 409));
        when(s3.getBucketTagging(ExternalTableMaterializer.SCRATCH_BUCKET)).thenReturn(Map.of());
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.getMessage(), containsString("not Floci scratch space"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
        verify(s3, never()).putBucketTagging(eq(ExternalTableMaterializer.SCRATCH_BUCKET), any());
        verify(s3, never()).deleteObject(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString());
    }

    @Test
    void tagsTheScratchBucketItCreatesAndReusesATaggedOne() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        materializer.ensureCurrent(backend, session(false), BINDING, "events");
        verify(s3).putBucketTagging(ExternalTableMaterializer.SCRATCH_BUCKET, Map.of(
                S3Service.INTERNAL_BUCKET_TAG_KEY, S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE));

        when(s3.createInternalBucket(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString()))
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
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<ExternalTableMaterializer.Outcome> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return materializer.ensureCurrent(backend, session(false), BINDING, "events");
            });
            awaitBlockedOrDone(secondThread, second);
            assertFalse(second.isDone());

            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS), equalTo(ExternalTableMaterializer.Outcome.LOADED));
            assertThat(second.get(10, TimeUnit.SECONDS), equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void dropWaitsForAnEarlierLoadBeforeDeletingTheMaterializedTable() throws Exception {
        AtomicReference<Table> stored = new AtomicReference<>(csvTable());
        AtomicInteger lookups = new AtomicInteger();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(glue.getTable("lake", "events")).thenAnswer(invocation -> {
            Table snapshot = stored.get();
            if (lookups.incrementAndGet() == 1) {
                reading.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            if (snapshot == null) {
                throw new AwsException("EntityNotFoundException", "gone", 400);
            }
            return snapshot;
        });
        doAnswer(invocation -> {
            stored.set(null);
            return null;
        }).when(glue).deleteTable("lake", "events");
        ExternalCatalogRegistry registry = mock(ExternalCatalogRegistry.class);
        when(registry.list(ACCOUNT, BINDING.clusterKey(), "dev")).thenReturn(List.of(BINDING));
        SpectrumQueryPreparation preparation = new SpectrumQueryPreparation(registry, materializer,
                new ExternalStatementParser(), glue, mock(ExternalMetadataWriter.class), materializer.catalogLocks());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ExternalTableMaterializer.Outcome> load = executor.submit(
                    () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                AtomicReference<Thread> dropThread = new AtomicReference<>();
                Future<Boolean> drop = executor.submit(() -> {
                    dropThread.set(Thread.currentThread());
                    return preparation.prepare("DROP TABLE analytics.events", session(false), backend);
                });
                awaitBlockedOrDone(dropThread, drop);
                assertFalse(drop.isDone());
                release.countDown();
                assertEquals(ExternalTableMaterializer.Outcome.LOADED, load.get(10, TimeUnit.SECONDS));
                assertTrue(drop.get(10, TimeUnit.SECONDS));
                assertEquals("DROP TABLE IF EXISTS \"analytics\".\"events\" RESTRICT", backend.statements.getLast());
                assertEquals(ExternalTableMaterializer.Outcome.NOT_EXTERNAL,
                        materializer.ensureCurrent(backend, session(false), BINDING, "events"));
                assertTrue(materializer.loadedTables(BINDING.clusterKey(), "dev", "analytics").isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void readsAParquetColumnMissingFromThePartitionFilesAsNull() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(duck.query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), isNull(), eq(ACCOUNT)))
                .thenReturn(List.of(schemaRow(EVENTS_FILE, "id")));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).query(argThat(sql -> sql.equals(
                "SELECT file_name, name FROM parquet_schema(['s3://bucket/events/p1.parquet'])")),
                isNull(), eq(ACCOUNT));
        verify(duck).execute(argThat(sql -> sql.contains("COALESCE(CAST(NULL AS VARCHAR), '\\N') AS \"name\"")
                && !sql.contains("CAST(\"name\"")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void matchesParquetColumnsToTheDeclaredNamesIgnoringCase() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(duck.query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), isNull(), eq(ACCOUNT)))
                .thenReturn(List.of(schemaRow(EVENTS_FILE, "ID"), schemaRow(EVENTS_FILE, "Name")));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("CAST(\"id\" AS VARCHAR)")
                && sql.contains("CAST(\"name\" AS VARCHAR)")
                && !sql.contains("CAST(NULL AS VARCHAR)")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void describesAndProjectsEachParquetPartitionOnItsOwn() {
        Table table = parquetPartitionedTable();
        Column amt = new Column();
        amt.setName("amt");
        amt.setType("int");
        List<Column> columns = new ArrayList<>(table.getStorageDescriptor().getColumns());
        columns.add(amt);
        table.getStorageDescriptor().setColumns(columns);
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition current = parquetPartitionAt(table, "s3://bucket/current/", "2026-01-02");
        Partition older = parquetPartitionAt(table, "s3://bucket/older/", "2026-01-01");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(older, current));
        when(duck.query(argThat(sql -> sql != null && sql.startsWith("SELECT file_name, name FROM parquet_schema")),
                isNull(), eq(ACCOUNT)))
                .thenReturn(List.of(schemaRow("s3://bucket/current/part-1.parquet", "id"),
                        schemaRow("s3://bucket/current/part-1.parquet", "name"),
                        schemaRow("s3://bucket/current/part-1.parquet", "amt"),
                        schemaRow("s3://bucket/older/part-1.parquet", "id"),
                        schemaRow("s3://bucket/older/part-1.parquet", "name")));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")
                && sql.contains("s3://bucket/current/part-1.parquet") && sql.contains("s3://bucket/older/part-1.parquet")),
                isNull(), eq(ACCOUNT));
        verify(duck).execute(argThat(sql -> sql.contains("CAST(\"amt\" AS VARCHAR)")
                && sql.contains("COALESCE(CAST(NULL AS VARCHAR), '\\N') AS \"amt\"")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void describesParquetFilesAsTheRoleSessionWhenS3AuthIsEnforced() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(s3.isAuthEnforced()).thenReturn(true);
        when(iam.resolvePrincipalContext(BINDING.iamRoleArn())).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject"],"Resource":"*"}
                ]}""")));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), isNull(),
                startsWith("ASIA"), anyString(), notNull());
        verify(duck, never()).query(anyString(), any(), eq(ACCOUNT));
    }

    @Test
    void readsEveryDeclaredColumnWhenDescribingTheParquetFilesFails() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(duck.query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), isNull(), eq(ACCOUNT)))
                .thenThrow(new IllegalStateException("sidecar rejected the statement"));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("CAST(\"name\" AS VARCHAR)")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void deniesTheLoadWhenDescribingTheParquetFilesIsDenied() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));
        when(duck.query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), isNull(), eq(ACCOUNT)))
                .thenThrow(new IllegalStateException("HTTP 403 Forbidden"));

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events"));

        assertThat(exception.sqlState(), equalTo("42501"));
        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
    }

    @Test
    void readsEveryDeclaredColumnWhenTheParquetFilesCannotBeDescribed() {
        Table table = parquetPartitionedTable();
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition partition = parquetPartition(table);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(partition));

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck).execute(argThat(sql -> sql.contains("CAST(\"id\" AS VARCHAR)")
                && sql.contains("CAST(\"name\" AS VARCHAR)")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void doesNotDescribeTheFilesOfATextTable() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());

        materializer.ensureCurrent(backend, session(false), BINDING, "events");

        verify(duck, never()).query(argThat(sql -> sql.startsWith("SELECT file_name, name FROM parquet_schema")), any(), anyString());
        verify(duck, never()).query(anyString(), any(), anyString(), anyString(), any());
    }

    private static final String DAY_ONE_SQL = "SELECT * FROM analytics.events WHERE day = '2026-01-01'";
    private static final String DAY_TWO_SQL = "SELECT * FROM analytics.events WHERE day = '2026-01-02'";

    private Table dayPartitionedTable() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "string")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        return table;
    }

    private Partition dayPartition(Table table, String day, String prefix, String etag) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://bucket/" + prefix);
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        descriptor.setColumns(table.getStorageDescriptor().getColumns());
        Partition partition = new Partition();
        partition.setValues(List.of(day));
        partition.setStorageDescriptor(descriptor);
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq(prefix), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", prefix + "part-1.csv", new byte[]{1}, "text/csv", etag)),
                        List.of(), false, null));
        return partition;
    }

    @Test
    void loadsOnlyThePartitionsTheWhereCanSelect() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_TWO_SQL),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/two/part-1.csv")
                && !sql.contains("s3://bucket/one/")), any(), anyString(), eq(ACCOUNT));
        verify(s3, never()).listObjectsWithPrefixes(eq("bucket"), eq("one/"), any(), anyInt(), any(), any());
    }

    @Test
    void addsAPartitionAnotherQueryNeedsWithoutRemovingTheOnesAlreadyLoaded() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));
        materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL);
        int loaded = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_TWO_SQL),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        List<String> added = backend.statements.subList(loaded, backend.statements.size());
        assertThat(added.stream().noneMatch(sql -> sql.contains("TRUNCATE") || sql.contains("DROP TABLE \"analytics\"")),
                equalTo(true));
        assertThat(added.stream().anyMatch(sql -> sql.contains("INSERT INTO \"analytics\".\"events\"")), equalTo(true));
        assertThat(added.stream().noneMatch(sql -> sql.contains("DELETE FROM")), equalTo(true));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", null),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
    }

    @Test
    void replacesOnlyAPartitionWhoseObjectsChanged() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));
        materializer.ensureCurrent(backend, session(false), BINDING, "events", null);
        dayPartition(table, "2026-01-01", "one/", "e1-changed");
        int loaded = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", null),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        List<String> changed = backend.statements.subList(loaded, backend.statements.size());
        assertThat(changed.stream().anyMatch(sql -> sql.contains(
                "DELETE FROM \"analytics\".\"events\" WHERE (\"day\" = CAST('2026-01-01' AS text))")), equalTo(true));
        assertThat(changed.stream().noneMatch(sql -> sql.contains("2026-01-02")), equalTo(true));
        verify(duck, times(2)).execute(anyString(), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void dropsTheRowsOfAPartitionGlueNoLongerHas() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));
        materializer.ensureCurrent(backend, session(false), BINDING, "events", null);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one));
        int loaded = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        List<String> dropped = backend.statements.subList(loaded, backend.statements.size());
        assertThat(dropped.stream().anyMatch(sql -> sql.contains(
                "DELETE FROM \"analytics\".\"events\" WHERE (\"day\" = CAST('2026-01-02' AS text))")), equalTo(true));
    }

    @Test
    void matchesTheNullPartitionRowsOfADeletedHiveDefaultPartition() {
        Table table = dayPartitionedTable();
        Partition defaultPartition = dayPartition(table, "__HIVE_DEFAULT_PARTITION__", "none/", "e0");
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(defaultPartition, one));
        materializer.ensureCurrent(backend, session(false), BINDING, "events", null);
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one));
        int loaded = backend.statements.size();

        materializer.ensureCurrent(backend, session(false), BINDING, "events", null);

        assertThat(backend.statements.subList(loaded, backend.statements.size()).stream()
                .anyMatch(sql -> sql.contains("WHERE (\"day\" IS NULL)")), equalTo(true));
    }

    @Test
    void reloadsTheTableWhenItsDefinitionChanges() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one));
        materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL);
        table.setVersionId("2");
        int loaded = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        assertThat(backend.statements.subList(loaded, backend.statements.size()).stream()
                .anyMatch(sql -> sql.contains("TRUNCATE")), equalTo(true));
    }

    @Test
    void doesNotReadAPartitionTheWhereExcludesEvenWhenItsObjectsAreDenied() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedGetObject(anyString(), anyString(), eq("bucket"), eq("two/part-1.csv"));

        materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_ONE_SQL);

        SpectrumSqlException denied = assertThrows(SpectrumSqlException.class,
                () -> materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_TWO_SQL));
        assertThat(denied.sqlState(), equalTo("42501"));
    }

    @Test
    void loadsEveryPartitionInATransactionAndOnAnExtendedQueryBackend() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));
        materializer.ensureCurrent(backend, session(true), BINDING, "events", DAY_ONE_SQL);
        RecordingBackend extended = new RecordingBackend();
        extended.publicationAllowed = false;
        materializer.ensureCurrent(extended, session(false), BINDING, "events", DAY_ONE_SQL);

        verify(duck, times(2)).execute(argThat(sql -> sql.contains("s3://bucket/one/part-1.csv")
                && sql.contains("s3://bucket/two/part-1.csv")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void loadsEveryPartitionWhenTheQueryHasNoPartitionPredicate() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        Partition two = dayPartition(table, "2026-01-02", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one, two));

        materializer.ensureCurrent(backend, session(false), BINDING, "events",
                "SELECT * FROM analytics.events WHERE id = 1");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/one/part-1.csv")
                && sql.contains("s3://bucket/two/part-1.csv")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void createsTheTableEmptyWhenNoPartitionIsSelected() {
        Table table = dayPartitionedTable();
        Partition one = dayPartition(table, "2026-01-01", "one/", "e1");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(one));

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events", DAY_TWO_SQL),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));

        verify(duck, never()).execute(anyString(), any(), anyString(), anyString());
        assertThat(backend.statements.stream().anyMatch(sql -> sql.contains("CREATE TABLE")), equalTo(true));
    }

    @Test
    void refreshingOnePartitionReloadsOtherPartitionsWhoseConvertedKeysOverlap() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "int")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition p1 = dayPartition(table, "1", "one/", "e1");
        Partition p01 = dayPartition(table, "01", "zero-one/", "e01");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(p1, p01));

        materializer.ensureCurrent(backend, session(false), BINDING, "events",
                "SELECT * FROM analytics.events WHERE day IN (1, 01)");

        Partition p1Changed = dayPartition(table, "1", "one/", "e1-new");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(p1Changed, p01));

        reset(duck);
        int loaded = backend.statements.size();
        materializer.ensureCurrent(backend, session(false), BINDING, "events",
                "SELECT * FROM analytics.events WHERE day = 1");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/one/part-1.csv")
                && sql.contains("s3://bucket/zero-one/part-1.csv")), any(), anyString(), eq(ACCOUNT));
        List<String> replaced = backend.statements.subList(loaded, backend.statements.size());
        assertThat(replaced.stream().anyMatch(sql -> sql.contains("TRUNCATE")), equalTo(true));
        assertThat(replaced.stream().noneMatch(sql -> sql.contains("DELETE FROM")), equalTo(true));
    }

    @Test
    void readsEveryPartitionWhenTwoKeysCastToTheSameValueEvenIfTheWhereExcludesOne() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "int")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition p1 = dayPartition(table, "1", "one/", "e1");
        Partition p01 = dayPartition(table, "01", "zero-one/", "e01");
        Partition p2 = dayPartition(table, "2", "two/", "e2");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(p1, p01, p2));

        materializer.ensureCurrent(backend, session(false), BINDING, "events",
                "SELECT * FROM analytics.events WHERE day = 2");

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/one/part-1.csv")
                && sql.contains("s3://bucket/zero-one/part-1.csv")
                && sql.contains("s3://bucket/two/part-1.csv")), any(), anyString(), eq(ACCOUNT));
    }

    @Test
    void keepsBothOverlappingPartitionsCurrentWhenNothingChanged() {
        Table table = csvTable();
        table.setPartitionKeys(List.of(new Column("day", "int")));
        when(glue.getTable("lake", "events")).thenReturn(table);
        Partition p1 = dayPartition(table, "1", "one/", "e1");
        Partition p01 = dayPartition(table, "01", "zero-one/", "e01");
        when(glue.getPartitions("lake", "events")).thenReturn(List.of(p1, p01));
        materializer.ensureCurrent(backend, session(false), BINDING, "events",
                "SELECT * FROM analytics.events WHERE day = 1");

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events",
                        "SELECT * FROM analytics.events WHERE day = 1"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
    }

    private static Map<String, Object> schemaRow(String file, String name) {
        return Map.of("file_name", file, "name", name);
    }

    private Partition parquetPartitionAt(Table table, String location, String day) {
        Partition partition = partitionAt("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", table);
        partition.getStorageDescriptor().setLocation(location);
        partition.setValues(List.of(day));
        String prefix = location.substring("s3://bucket/".length());
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq(prefix), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", prefix + "part-1.parquet", new byte[]{1},
                                "application/octet-stream", "etag-" + day)), List.of(), false, null));
        return partition;
    }

    private Table parquetPartitionedTable() {
        Table table = csvTable();
        table.getStorageDescriptor().setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        table.setPartitionKeys(List.of(new Column("day", "string")));
        return table;
    }

    private Partition parquetPartition(Table table) {
        Partition partition = partitionAt("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", table);
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.parquet", new byte[]{1}, "application/octet-stream", "etag1")),
                        List.of(), false, null));
        return partition;
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

        verify(s3).authorizeSignedListBucket(anyString(), anyString(), eq("bucket"), any());
        verify(s3).authorizeSignedGetObject(anyString(), anyString(), eq("bucket"), eq("events/p1.csv"));
        verify(iam).unregisterSession(eq(ACCOUNT), anyString());
    }

    @Test
    void releasesTheRoleSessionWhenAuthorizationFails() {
        when(glue.getTable("lake", "events")).thenReturn(csvTable());
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedListBucket(anyString(), anyString(), eq("bucket"), any());

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
