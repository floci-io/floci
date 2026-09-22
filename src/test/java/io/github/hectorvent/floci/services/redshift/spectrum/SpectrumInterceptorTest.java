package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpectrumInterceptorTest {

    private SpectrumCatalog catalog;
    private SpectrumMaterializer materializer;
    private SpectrumInterceptor interceptor;

    @BeforeEach
    void setUp() {
        catalog = mock(SpectrumCatalog.class);
        materializer = mock(SpectrumMaterializer.class);
        interceptor = new SpectrumInterceptor(catalog, new SpectrumStatementParser(), new SpectrumQueryClassifier(),
                null, mock(SpectrumS3Reader.class), materializer);
    }

    @Test
    void storesExternalSchemaAndTableDdl() {
        SpectrumInterceptor.Decision schema = interceptor.intercept(
                "CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'",
                "000000000000", "dev", null);
        SpectrumInterceptor.Decision table = interceptor.intercept(
                "CREATE EXTERNAL TABLE analytics.events (id INTEGER) STORED AS TEXTFILE LOCATION 's3://b/events/'",
                "000000000000", "dev", null);

        assertInstanceOf(SpectrumInterceptor.Decision.Handled.class, schema);
        assertInstanceOf(SpectrumInterceptor.Decision.Handled.class, table);
    }

    @Test
    void forwardsLocalQueryAndRewritesCatalogResolvedExternalQuery() {
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)), "s3://b/events/", ',', '"', '\\', "\\N", 0);
        SpectrumExternalSchema schema = new SpectrumExternalSchema("000000000000", "dev", "analytics",
                "s3://b/root/", null);
        SpectrumMaterializer.Materialization materialization = new SpectrumMaterializer.Materialization(
                "spectrum_tmp_x", table.columns());
        when(catalog.table("000000000000", "dev", "analytics", "events")).thenReturn(java.util.Optional.of(table));
        when(catalog.schema("000000000000", "dev", "analytics")).thenReturn(java.util.Optional.of(schema));
        when(materializer.materialize(any(), any(), any(), any())).thenReturn(materialization);

        assertInstanceOf(SpectrumInterceptor.Decision.Forward.class,
                interceptor.intercept("SELECT * FROM local.events", "000000000000", "dev", null));
        SpectrumInterceptor.Decision decision = interceptor.intercept(
                "SELECT * FROM analytics.events", "000000000000", "dev", null);
        SpectrumInterceptor.Decision.Rewritten rewritten = assertInstanceOf(
                SpectrumInterceptor.Decision.Rewritten.class, decision);
        assertEquals("SELECT * FROM \"spectrum_tmp_x\"", rewritten.sql());
    }
}
