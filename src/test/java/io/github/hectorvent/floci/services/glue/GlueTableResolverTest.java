package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlueTableResolverTest {

    private static Column column(String name, String type) {
        Column column = new Column();
        column.setName(name);
        column.setType(type);
        return column;
    }

    private static Table table(String format, String location, List<Column> columns) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setInputFormat(format);
        descriptor.setLocation(location);
        descriptor.setColumns(columns);
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(descriptor);
        return table;
    }

    @Test
    void readPlanForParquetUsesLocationGlob() {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table(
                "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", "s3://bucket/events/",
                List.of(column("id", "int"))));
        assertThat(plan.fromClause(), equalTo("read_parquet('s3://bucket/events/**', union_by_name = true)"));
        assertThat(plan.iceberg(), is(false));
    }

    @Test
    void readPlanForIcebergUsesMetadataLocation() {
        Table table = table(null, "s3://bucket/iceberg/", List.of(column("id", "int")));
        table.setParameters(Map.of("table_type", "ICEBERG", "metadata_location", "s3://bucket/iceberg/metadata/v1.json"));
        assertThat(GlueTableResolver.readPlan(table).fromClause(), equalTo("iceberg_scan('s3://bucket/iceberg/metadata/v1.json')"));
    }

    @Test
    void readPlanAppendsPartitionKeysWithoutDuplicates() {
        Table table = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events",
                List.of(column("id", "int"), column("dt", "string")));
        table.setPartitionKeys(List.of(column("DT", "string"), column("region", "string")));
        assertThat(GlueTableResolver.readPlan(table).columns().stream().map(Column::getName).toList(), contains("id", "dt", "region"));
    }

    @Test
    void readPlanRejectsMissingLocation() {
        assertThrows(IllegalArgumentException.class, () -> GlueTableResolver.readPlan(table("input", " ", List.of())));
    }

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

    @Test
    void readPlanUsesExplicitCsvOptionsInsteadOfSniffingWhenTheTableDeclaresThem() {
        Table table = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events/",
                List.of(column("id", "int"), column("name", "string")));
        table.setParameters(Map.of("skip.header.line.count", "0", "serialization.null.format", "NA"));
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setParameters(Map.of("field.delim", "|", "quoteChar", "'", "escapeChar", "\\"));
        table.getStorageDescriptor().setSerdeInfo(serde);

        String from = GlueTableResolver.readPlan(table).fromClause();

        assertThat(from, equalTo("read_csv('s3://bucket/events/**', header = false, delim = '|', quote = '''', "
                + "escape = '\\', nullstr = 'NA', columns = {'id': 'VARCHAR', 'name': 'VARCHAR'})"));
    }

    @Test
    void readPlanDefaultsTheDelimiterToACommaWhenOnlyTheHeaderCountIsDeclared() {
        Table table = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events/",
                List.of(column("id", "int")));
        table.setParameters(Map.of("skip.header.line.count", "0"));

        String from = GlueTableResolver.readPlan(table).fromClause();

        assertThat(from, equalTo("read_csv('s3://bucket/events/**', header = false, delim = ',', "
                + "columns = {'id': 'VARCHAR'})"));
    }

    @Test
    void readPlanTreatsOneSkippedLineAsAHeaderAndMoreAsSkippedRows() {
        Table oneLine = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events/",
                List.of(column("id", "int")));
        oneLine.setParameters(Map.of("skip.header.line.count", "1"));
        Table twoLines = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events/",
                List.of(column("id", "int")));
        twoLines.setParameters(Map.of("skip.header.line.count", "2"));

        assertThat(GlueTableResolver.readPlan(oneLine).fromClause(), containsString("header = true"));
        assertThat(GlueTableResolver.readPlan(twoLines).fromClause(), containsString("header = false, skip = 2"));
    }

    @Test
    void readPlanKeepsSniffingWhenTheTableDeclaresNoCsvOptions() {
        Table table = table("org.apache.hadoop.mapred.TextInputFormat", "s3://bucket/events/",
                List.of(column("id", "int")));

        assertThat(GlueTableResolver.readPlan(table).fromClause(), equalTo("read_csv_auto('s3://bucket/events/**')"));
    }
}
