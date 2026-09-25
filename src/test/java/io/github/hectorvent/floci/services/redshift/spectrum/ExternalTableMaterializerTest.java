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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        backend = new RecordingBackend();
        materializer = new ExternalTableMaterializer(duck, glue, s3, iam, config);
        S3Object scratch = new S3Object(ExternalTableMaterializer.SCRATCH_BUCKET, "scratch.csv",
                "id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8), "text/csv");
        when(s3.getObject(eq(ExternalTableMaterializer.SCRATCH_BUCKET), anyString())).thenReturn(scratch);
        when(s3.listObjectsWithPrefixes(eq("bucket"), eq("events/"), eq(""), eq(1000), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("bucket", "events/p1.csv", new byte[]{1}, "text/csv", "etag1")),
                        List.of(), false, null));
        when(s3.isAuthEnforced()).thenReturn(false);
    }

    @Test
    void loadsThroughAStagingTableAndCachesUnchangedData() {
        Table table = csvTable();
        when(glue.getTable("lake", "events")).thenReturn(table);

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.LOADED));
        assertThat(backend.statements.get(0), containsString("CREATE TABLE \"analytics\".\"events__stg\""));
        assertThat(backend.statements.get(0), containsString("\"id\" integer, \"name\" text"));
        assertThat(backend.statements.get(1), containsString("COPY \"analytics\".\"events__stg\" FROM STDIN"));
        assertThat(backend.statements.get(2), containsString("RENAME TO \"events\""));
        verify(duck).execute(argThat(sql -> sql.contains("LIMIT 1001")), isNull(), anyString(), eq(ACCOUNT));
        int size = backend.statements.size();

        assertThat(materializer.ensureCurrent(backend, session(false), BINDING, "events"),
                equalTo(ExternalTableMaterializer.Outcome.CURRENT));
        assertThat(backend.statements.size(), equalTo(size));
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

        verify(duck).execute(argThat(sql -> sql.contains("s3://bucket/archived/day=2026-09-25/**")
                && sql.contains("CAST('2026-09-25' AS VARCHAR)") && sql.contains("AS \"day\"")),
                any(), anyString(), eq(ACCOUNT));
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

        verify(duck).execute(argThat(sql -> sql.contains("read_csv('s3://bucket/events/**'")
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
