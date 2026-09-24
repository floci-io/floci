package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalTableMaterializerTest {
    private static final String ACCOUNT = "000000000000";
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(ACCOUNT, ACCOUNT + ":c",
            "dev", "analytics", "lake", "arn:aws:iam::000000000000:role/R");
    private FlociDuckClient duck;
    private GlueService glue;
    private S3Service s3;
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
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().spectrumMaxRows()).thenReturn(1000L);
        when(config.defaultRegion()).thenReturn("us-east-1");
        backend = new RecordingBackend();
        materializer = new ExternalTableMaterializer(duck, glue, s3, mock(IamService.class), config);
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
}
