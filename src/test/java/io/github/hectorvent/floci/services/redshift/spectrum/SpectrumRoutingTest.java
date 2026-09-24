package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectrumRoutingTest {
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:c", "dev", List.of(), false);
    private static final BackendSql BACKEND = new BackendSql() {
        @Override public void execute(String sql) { }
        @Override public long copyIn(String sql, InputStream data) { return 0; }
    };
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(
            "000000000000", "000000000000:c", "dev", "a", "glue_db", "arn:aws:iam::000000000000:role/Spectrum");

    private ExternalSchemaService service;
    private SpectrumMaterializer materializer;
    private SpectrumInterceptor interceptor;

    @BeforeEach
    void setUp() {
        service = mock(ExternalSchemaService.class);
        materializer = mock(SpectrumMaterializer.class);
        SpectrumS3Reader reader = mock(SpectrumS3Reader.class);
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().spectrumEnabled()).thenReturn(true);
        when(service.referencesIn(any(), eq(SESSION)))
                .thenReturn(List.of(new ExternalReferenceScanner.Reference("a", "t")));
        when(service.resolveGlueTable(any(), eq(SESSION))).thenReturn(Optional.of(new ExternalSchemaService.BoundGlueTable(BINDING, csvTable("TEXTFILE", false))));
        when(materializer.nextIdentifier()).thenReturn("spectrum_tmp_test");
        interceptor = new SpectrumInterceptor(new SpectrumStatementParser(), new ExternalStatementParser(),
                new SpectrumQueryClassifier(), new SpectrumGlueCsvAdapter(), materializer, reader, service, config);
    }

    @Test
    void simpleGlueCsvSelectRoutesToLegacyPhaseOne() {
        SpectrumInterceptor.Plan plan = interceptor.plan("SELECT * FROM a.t", SESSION);

        assertThat(plan, instanceOf(SpectrumInterceptor.Plan.PhaseOneQuery.class));
    }

    @Test
    void joinsAndBindParametersStayOnGeneralGluePath() {
        assertThat(interceptor.plan("SELECT * FROM a.t JOIN a.u ON t.id = u.id", SESSION),
                instanceOf(SpectrumInterceptor.Plan.Load.class));
        assertThat(interceptor.plan("SELECT * FROM a.t WHERE id = $1", SESSION),
                instanceOf(SpectrumInterceptor.Plan.Load.class));
    }

    @Test
    void parquetAndPartitionedCsvStayOnGeneralGluePath() {
        when(service.resolveGlueTable(any(), eq(SESSION)))
                .thenReturn(Optional.of(new ExternalSchemaService.BoundGlueTable(BINDING, csvTable("PARQUET", false))))
                .thenReturn(Optional.of(new ExternalSchemaService.BoundGlueTable(BINDING, csvTable("TEXTFILE", true))));

        assertThat(interceptor.plan("SELECT * FROM a.t", SESSION), instanceOf(SpectrumInterceptor.Plan.Load.class));
        assertThat(interceptor.plan("SELECT * FROM a.t", SESSION), instanceOf(SpectrumInterceptor.Plan.Load.class));
    }

    @Test
    void localQueryStaysOnPostgres() {
        when(service.referencesIn("SELECT 1", SESSION)).thenReturn(List.of());

        assertThat(interceptor.plan("SELECT 1", SESSION), instanceOf(SpectrumInterceptor.Plan.Forward.class));
    }

    @Test
    void phaseOneExecutionMaterializesRowsAndReturnsRewrittenSql() {
        SpectrumInterceptor.Plan plan = interceptor.plan("SELECT * FROM a.t", SESSION);
        SpectrumMaterializer.Materialization loaded = new SpectrumMaterializer.Materialization("spectrum_tmp_test", List.of());
        when(materializer.materialize(eq(BACKEND), eq(SESSION), any(), any(), any(), eq("spectrum_tmp_test")))
                .thenReturn(loaded);

        SpectrumInterceptor.Decision result = interceptor.execute(plan, SESSION, BACKEND);

        assertThat(result, equalTo(new SpectrumInterceptor.Decision.Rewritten("SELECT * FROM \"spectrum_tmp_test\"", loaded)));
        verify(materializer).materialize(eq(BACKEND), eq(SESSION), any(), any(), any(), eq("spectrum_tmp_test"));
    }

    @Test
    void phaseOneReadFailurePropagatesInsteadOfForwardingToSameNamedNativeTable() {
        SpectrumInterceptor.Plan plan = interceptor.plan("SELECT * FROM a.t", SESSION);
        when(materializer.materialize(eq(BACKEND), eq(SESSION), any(), any(), any(), eq("spectrum_tmp_test")))
                .thenThrow(new SpectrumReadException("42501", "S3 access denied"));

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> interceptor.execute(plan, SESSION, BACKEND));

        assertThat(error.sqlState(), equalTo("42501"));
    }

    private static Table csvTable(String format, boolean partitioned) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(List.of(new Column("id", "int"), new Column("value", "string")));
        descriptor.setLocation("s3://spectrum-data/table/");
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        descriptor.setSerdeInfo(new StorageDescriptor.SerDeInfo());
        descriptor.getSerdeInfo().setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        Table table = new Table();
        table.setName("t");
        table.setTableType("EXTERNAL_TABLE");
        table.setStorageDescriptor(descriptor);
        table.setParameters(Map.of());
        if (format.equals("PARQUET")) {
            descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        }
        if (partitioned) {
            table.setPartitionKeys(List.of(new Column("day", "string")));
        }
        return table;
    }
}
