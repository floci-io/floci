package io.floci.aws.services.glue;

import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
    void listsParquetSchemasEscapingGlobCharactersAndQuotes() {
        assertThat(GlueTableResolver.parquetSchemaSql(List.of("s3://bucket/a[1]/it's*.parquet", "s3://bucket/b.parquet")),
                equalTo("SELECT file_name, name FROM parquet_schema("
                        + "['s3://bucket/a[[]1]/it''s[*].parquet', 's3://bucket/b.parquet'])"));
    }

    @Test
    void readsParquetOnlyForAParquetTableThatIsNotIceberg() {
        Table parquet = table("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", "s3://bucket/events/",
                List.of(column("id", "int")));
        assertThat(GlueTableResolver.readsParquet(parquet), is(true));
        assertThat(GlueTableResolver.readsParquet(table("org.apache.hadoop.mapred.TextInputFormat",
                "s3://bucket/events/", List.of(column("id", "int")))), is(false));
        parquet.setParameters(Map.of("table_type", "ICEBERG", "metadata_location", "s3://bucket/m/v1.json"));
        assertThat(GlueTableResolver.readsParquet(parquet), is(false));
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

    private static Table templatedTable(String template, Column... partitionKeys) {
        Table table = table("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", "s3://bucket/events/",
                List.of(column("id", "string")));
        table.setPartitionKeys(List.of(partitionKeys));
        table.setParameters(Map.of("projection.enabled", "true", "storage.location.template", template));
        return table;
    }

    /** A string key is the matched text itself; any other type is cast, so a non-value yields NULL, not an error. */
    @Test
    void templatedKeysAreCastToTheirDeclaredTypes() {
        Table table = templatedTable("s3://bucket/events/${region}/${year}/${dt}/",
                column("region", "string"), column("year", "int"), column("dt", "date"));

        String regex = "'^s3://bucket/events/([^/]+)/([^/]+)/([^/]+)/'";
        assertThat(GlueTableResolver.buildProjection(table), equalTo("\"id\" AS \"id\", "
                + "regexp_extract(\"$path\", " + regex + ", 1) AS \"region\", "
                + "TRY_CAST(regexp_extract(\"$path\", " + regex + ", 2) AS INTEGER) AS \"year\", "
                + "TRY_CAST(regexp_extract(\"$path\", " + regex + ", 3) AS DATE) AS \"dt\""));
        assertThat(GlueTableResolver.pathFilter(table), equalTo("regexp_matches(\"$path\", " + regex + ")"
                + " AND TRY_CAST(regexp_extract(\"$path\", " + regex + ", 2) AS INTEGER) IS NOT NULL"
                + " AND TRY_CAST(regexp_extract(\"$path\", " + regex + ", 3) AS DATE) IS NOT NULL"));
    }

    /**
     * DuckDB's scalar {@code regexp_extract} stops at group 9, so a key at group 10 is read through
     * the struct form, and the keys before it keep the scalar form.
     */
    @Test
    void aKeyPastTheNinthGroupIsExtractedThroughTheStructForm() {
        Table table = templatedTable("s3://bucket/events/${account}/${team}/${service}/${env}/${cluster}/"
                        + "${zone}/${rack}/${host}/${region}/${dt}/",
                column("account", "string"), column("team", "string"), column("service", "string"),
                column("env", "string"), column("cluster", "string"), column("zone", "string"),
                column("rack", "string"), column("host", "string"), column("region", "string"),
                column("dt", "date"));

        String regex = "'^s3://bucket/events/" + "([^/]+)/".repeat(10) + "'";
        String projection = GlueTableResolver.buildProjection(table);
        assertThat(projection, containsString("regexp_extract(\"$path\", " + regex + ", 9) AS \"region\""));
        assertThat(projection, containsString("TRY_CAST(regexp_extract(\"$path\", " + regex + ", "
                + "['g1', 'g2', 'g3', 'g4', 'g5', 'g6', 'g7', 'g8', 'g9', 'g10']).g10 AS DATE) AS \"dt\""));
    }

    @Test
    void templatedReadAddsTheObjectPathAndTurnsOffHivePartitioning() {
        Table table = templatedTable("s3://bucket/events/${region}/", column("region", "string"));

        assertThat(GlueTableResolver.readExpression(table, "read_json_auto", "s3://bucket/events/*"),
                equalTo("read_json_auto('s3://bucket/events/*/**', hive_partitioning = false, filename = '$path')"));
    }

    @Test
    void singleQuotesInTheTemplateAreEscapedInTheRegexLiteral() {
        Table table = templatedTable("s3://bucket/o'brien/${region}/", column("region", "string"));

        assertThat(GlueTableResolver.pathFilter(table), equalTo("regexp_matches(\"$path\", '^s3://bucket/o''brien/([^/]+)/')"));
    }

    /** Tables without a template read and project exactly as they did, and an Iceberg table ignores one. */
    @Test
    void tablesWithoutATemplateAreReadAsBefore() {
        Table plain = table("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", "s3://bucket/events/",
                List.of(column("id", "string")));
        Table iceberg = templatedTable("s3://bucket/events/${region}/", column("region", "string"));
        iceberg.setParameters(Map.of("projection.enabled", "true", "table_type", "ICEBERG",
                "storage.location.template", "s3://bucket/events/${region}/"));

        for (Table table : List.of(plain, iceberg)) {
            assertThat(GlueTableResolver.readExpression(table, "read_parquet", "s3://bucket/events"),
                    equalTo("read_parquet('s3://bucket/events/**', union_by_name = true)"));
            assertThat(GlueTableResolver.pathFilter(table), is(nullValue()));
        }
        assertThat(GlueTableResolver.buildProjection(iceberg), equalTo("\"id\" AS \"id\", \"region\" AS \"region\""));
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
