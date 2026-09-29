package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class GlueTableResolverTest {

    @Test
    void inferReadFunctionDetectsParquetFromInputFormat() {
        StorageDescriptor sd = new StorageDescriptor();
        sd.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        Table table = new Table();
        table.setStorageDescriptor(sd);

        assertThat(GlueTableResolver.inferReadFunction(table), equalTo("read_parquet"));
    }

    @Test
    void inferReadFunctionDefaultsToCsvAutoForNullTable() {
        assertThat(GlueTableResolver.inferReadFunction(null), equalTo("read_csv_auto"));
    }

    @Test
    void readExpressionEscapesSingleQuotesInLocation() {
        String expr = GlueTableResolver.readExpression("read_csv_auto", "s3://bucket/o'brien");
        assertThat(expr, equalTo("read_csv_auto('s3://bucket/o''brien/**')"));
    }

    @Test
    void readExpressionForParquetAddsUnionByName() {
        String expr = GlueTableResolver.readExpression("read_parquet", "s3://bucket/data");
        assertThat(expr, equalTo("read_parquet('s3://bucket/data/**', union_by_name = true)"));
    }

    @Test
    void buildProjectionReturnsStarForTableWithNoDeclaredColumns() {
        Table table = new Table();
        table.setStorageDescriptor(new StorageDescriptor());

        assertThat(GlueTableResolver.buildProjection(table), equalTo("*"));
    }

    @Test
    void buildProjectionQuotesDeclaredColumnNames() {
        Column tenantId = new Column();
        tenantId.setName("tenantId");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setColumns(List.of(tenantId));
        Table table = new Table();
        table.setStorageDescriptor(sd);

        assertThat(GlueTableResolver.buildProjection(table), equalTo("\"tenantId\" AS \"tenantId\""));
    }

    @Test
    void isIcebergTableFalseWhenParametersMissing() {
        assertThat(GlueTableResolver.isIcebergTable(new Table()), is(false));
    }
}
