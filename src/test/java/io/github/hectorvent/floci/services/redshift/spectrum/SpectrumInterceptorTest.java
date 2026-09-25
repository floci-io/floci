package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectrumInterceptorTest {
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:c", "dev", List.of(), false);
    private static final BackendSql BACKEND = new BackendSql() {
        @Override public void execute(String sql) { }
        @Override public long copyIn(String sql, InputStream data) { return 0; }
    };
    private ExternalSchemaService service;
    private EmulatorConfig config;
    private SpectrumInterceptor interceptor;

    @BeforeEach
    void setUp() {
        service = mock(ExternalSchemaService.class);
        config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().spectrumEnabled()).thenReturn(true);
        interceptor = new SpectrumInterceptor(new SpectrumStatementParser(), new ExternalStatementParser(),
                new SpectrumQueryClassifier(), new SpectrumGlueCsvAdapter(), mock(SpectrumMaterializer.class),
                mock(SpectrumS3Reader.class), service, config);
    }

    @Test
    void externalDdlIsHandledWithTheCommandTag() {
        when(service.handles(any(), eq(SESSION))).thenReturn(true);
        when(service.execute(any(), eq(SESSION), eq(BACKEND))).thenReturn(Optional.of("CREATE SCHEMA"));
        SpectrumInterceptor.Decision result = interceptor.intercept(
                "CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'd' IAM_ROLE 'arn:aws:iam::000000000000:role/R'", SESSION, BACKEND);
        assertThat(result, equalTo(new SpectrumInterceptor.Decision.Handled("CREATE SCHEMA")));
    }

    @Test
    void legacyExternalSchemaSyntaxRetainsCreateGlueDatabaseOption() {
        when(service.handles(any(), eq(SESSION))).thenReturn(true);
        SpectrumInterceptor.Plan plan = interceptor.plan(
                "CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'd' IAM_ROLE 'r' CREATE EXTERNAL DATABASE IF NOT EXISTS", SESSION);

        assertThat(plan, instanceOf(SpectrumInterceptor.Plan.Ddl.class));
        ExternalStatement.CreateSchema statement = (ExternalStatement.CreateSchema) ((SpectrumInterceptor.Plan.Ddl) plan).statement();
        assertThat(statement.createDatabaseIfNotExists(), is(true));
    }

    @Test
    void externalReadsLoadBeforeForwarding() {
        List<ExternalReferenceScanner.Reference> refs = List.of(new ExternalReferenceScanner.Reference("a", "t"));
        when(service.referencesIn("SELECT * FROM a.t", SESSION)).thenReturn(refs);
        assertThat(interceptor.intercept("SELECT * FROM a.t", SESSION, BACKEND), instanceOf(SpectrumInterceptor.Decision.Forward.class));
        verify(service).loadReferences(refs, SESSION, BACKEND);
    }

    @Test
    void catalogViewsRefreshFirst() {
        when(service.touchesCatalogViews("SELECT * FROM svv_external_tables")).thenReturn(true);
        interceptor.intercept("SELECT * FROM svv_external_tables", SESSION, BACKEND);
        verify(service).refreshMetadata(SESSION, BACKEND);
    }

    @Test
    void catalogViewQueryAlsoLoadsReferencedExternalTables() {
        String sql = "SELECT * FROM svv_external_tables v JOIN analytics.events e ON true";
        List<ExternalReferenceScanner.Reference> references = List.of(
                new ExternalReferenceScanner.Reference("analytics", "events"));
        when(service.touchesCatalogViews(sql)).thenReturn(true);
        when(service.referencesIn(sql, SESSION)).thenReturn(references);

        interceptor.intercept(sql, SESSION, BACKEND);

        verify(service).refreshMetadata(SESSION, BACKEND);
        verify(service).loadReferences(references, SESSION, BACKEND);
    }

    @Test
    void nativeStatementsDoNotLoadAndDisabledSpectrumForwards() {
        assertThat(interceptor.intercept("SELECT 1", SESSION, BACKEND), instanceOf(SpectrumInterceptor.Decision.Forward.class));
        verify(service, never()).loadReferences(any(), any(), any());
        when(config.services().redshift().spectrumEnabled()).thenReturn(false);
        assertThat(interceptor.intercept("CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'd' IAM_ROLE 'r'", SESSION, BACKEND),
                instanceOf(SpectrumInterceptor.Decision.Forward.class));
    }

    @Test
    void nativeDropStatementsAreForwardedInsteadOfPlannedAsExternalDdl() {
        assertThat(interceptor.plan("DROP TABLE public.t", SESSION), instanceOf(SpectrumInterceptor.Plan.Forward.class));
        assertThat(interceptor.plan("DROP SCHEMA local_schema", SESSION), instanceOf(SpectrumInterceptor.Plan.Forward.class));
        verify(service, never()).execute(any(), any(), any());
    }

    @Test
    void dropOfABoundExternalSchemaIsPlannedAsExternalDdl() {
        when(service.handles(any(), eq(SESSION))).thenReturn(true);

        assertThat(interceptor.plan("DROP SCHEMA analytics", SESSION), instanceOf(SpectrumInterceptor.Plan.Ddl.class));
    }
}
