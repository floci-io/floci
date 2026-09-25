package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.ColumnDefinition;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.CreateTable;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.PartitionSpec;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.TableFormat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlueTableBuilderTest {
    @Test
    void buildsGlueStorageDescriptorForParquetAndCsv() {
        CreateTable parquet = new CreateTable("analytics", "events",
                List.of(new ColumnDefinition("id", "int")), List.of(), TableFormat.PARQUET,
                "s3://bucket/events/", null, null, Map.of());
        Table table = GlueTableBuilder.toGlueTable(parquet);
        assertThat(table.getTableType(), equalTo("EXTERNAL_TABLE"));
        assertThat(table.getStorageDescriptor().getInputFormat(),
                equalTo("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"));
        assertThat(table.getParameters().get("EXTERNAL"), equalTo("TRUE"));

        CreateTable csv = new CreateTable("analytics", "csv_events",
                List.of(new ColumnDefinition("id", "int")), List.of(), TableFormat.TEXTFILE,
                "s3://bucket/csv/", "|", null, Map.of());
        assertThat(GlueTableBuilder.toGlueTable(csv).getStorageDescriptor().getSerdeInfo()
                .getParameters().get("field.delim"), equalTo("|"));
    }

    @Test
    void buildsPartitionValuesInGlueKeyOrder() {
        CreateTable statement = new CreateTable("analytics", "events",
                List.of(new ColumnDefinition("id", "int")),
                List.of(new ColumnDefinition("dt", "string"), new ColumnDefinition("region", "string")),
                TableFormat.PARQUET, "s3://bucket/events/", null, null, Map.of());
        Table table = GlueTableBuilder.toGlueTable(statement);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("region", "eu");
        values.put("dt", "2024-01-01");

        Partition partition = GlueTableBuilder.toGluePartition(table,
                new PartitionSpec(values, "s3://bucket/events/dt=2024-01-01/region=eu/"));
        assertThat(partition.getValues(), equalTo(List.of("2024-01-01", "eu")));
        assertThat(partition.getStorageDescriptor().getLocation(),
                equalTo("s3://bucket/events/dt=2024-01-01/region=eu/"));
    }

    @Test
    void openCsvTableKeepsItsSerdePropertiesAndNoDefaultFieldDelimiter() {
        CreateTable statement = new CreateTable("analytics", "events",
                List.of(new ColumnDefinition("id", "int")), List.of(), TableFormat.TEXTFILE,
                "s3://bucket/events/", null, "org.apache.hadoop.hive.serde2.OpenCSVSerde", Map.of(),
                Map.of("separatorChar", "|", "quoteChar", "\""));

        Map<String, String> parameters = GlueTableBuilder.toGlueTable(statement)
                .getStorageDescriptor().getSerdeInfo().getParameters();

        assertThat(parameters.get("separatorChar"), equalTo("|"));
        assertThat(parameters.get("quoteChar"), equalTo("\""));
        assertThat(parameters.containsKey("field.delim"), equalTo(false));
        assertThat(GlueTableBuilder.toGlueTable(statement).getStorageDescriptor().getSerdeInfo().getSerializationLibrary(),
                equalTo("org.apache.hadoop.hive.serde2.OpenCSVSerde"));
    }

    @Test
    void rejectsPartitionLocationsOutsideS3AndKeysTheTableDoesNotDeclare() {
        Table table = GlueTableBuilder.toGlueTable(new CreateTable("analytics", "events", List.of(),
                List.of(new ColumnDefinition("dt", "string")), TableFormat.PARQUET,
                "s3://bucket/events/", null, null, Map.of()));

        SpectrumSqlException location = assertThrows(SpectrumSqlException.class, () ->
                GlueTableBuilder.toGluePartition(table, new PartitionSpec(Map.of("dt", "x"), "/local/path/")));
        SpectrumSqlException extraKey = assertThrows(SpectrumSqlException.class, () ->
                GlueTableBuilder.toGluePartition(table, new PartitionSpec(Map.of("dt", "x", "extra", "y"), "s3://b/x/")));

        assertThat(location.sqlState(), equalTo("22023"));
        assertThat(extraKey.sqlState(), equalTo("22023"));
    }

    @Test
    void rejectsMissingPartitionKeys() {
        Table table = GlueTableBuilder.toGlueTable(new CreateTable("analytics", "events", List.of(),
                List.of(new ColumnDefinition("dt", "string")), TableFormat.PARQUET,
                "s3://bucket/events/", null, null, Map.of()));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () ->
                GlueTableBuilder.toGluePartition(table, new PartitionSpec(Map.of("other", "x"), "s3://b/x/")));
        assertThat(error.sqlState(), equalTo("22023"));
    }
}
