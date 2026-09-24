package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumGlueCsvAdapterTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/Reader";
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(
            ACCOUNT, "cluster-1", "dev", "analytics", "warehouse", ROLE);

    private final SpectrumGlueCsvAdapter adapter = new SpectrumGlueCsvAdapter();

    @Test
    void adaptsDeclaredTextfileColumnsAndHeaderCount() {
        Table table = csvTable("s3://events-bucket/events/");
        table.getStorageDescriptor().setColumns(List.of(
                new Column("id", "int"), new Column("name", "string"), new Column("code", "char")));
        table.setParameters(Map.of("EXTERNAL", "TRUE", "skip.header.line.count", "1"));

        SpectrumGlueCsvAdapter.CsvTable adapted = adapter.adapt(BINDING, table, ACCOUNT, "dev").orElseThrow();

        assertEquals("s3://events-bucket/events/", adapted.table().location());
        assertEquals(List.of(
                new SpectrumColumn("id", SpectrumColumn.Type.INTEGER),
                new SpectrumColumn("name", SpectrumColumn.Type.VARCHAR),
                new SpectrumColumn("code", SpectrumColumn.Type.CHAR)), adapted.table().columns());
        assertEquals(',', adapted.table().delimiter());
        assertEquals(1, adapted.table().headerLines());
        assertEquals(ACCOUNT, adapted.schema().accountId());
        assertEquals("dev", adapted.schema().databaseName());
        assertEquals("analytics", adapted.schema().schemaName());
        assertEquals(ROLE, adapted.schema().iamRoleArn());
    }

    @Test
    void adaptsExplicitQuoteAndEscapeProperties() {
        Table table = csvTable("s3://events-bucket/events/");
        StorageDescriptor.SerDeInfo serde = table.getStorageDescriptor().getSerdeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.OpenCSVSerde");
        serde.setParameters(Map.of("separatorChar", "|", "quoteChar", "~", "escapeChar", "!"));

        SpectrumExternalTable adapted = adapter.adapt(BINDING, table, ACCOUNT, "dev").orElseThrow().table();

        assertEquals('|', adapted.delimiter());
        assertEquals('~', adapted.quote());
        assertEquals('!', adapted.escape());
    }

    @Test
    void refusesMissingLocationUnsupportedSerdeAndPartitionedTables() {
        assertTrue(adapter.adapt(BINDING, csvTable(null), ACCOUNT, "dev").isEmpty());

        Table unsupportedSerde = csvTable("s3://events-bucket/events/");
        unsupportedSerde.getStorageDescriptor().getSerdeInfo()
                .setSerializationLibrary("org.openx.data.jsonserde.JsonSerDe");
        assertTrue(adapter.adapt(BINDING, unsupportedSerde, ACCOUNT, "dev").isEmpty());

        Table partitioned = csvTable("s3://events-bucket/events/");
        partitioned.setPartitionKeys(List.of(new Column("day", "date")));
        assertTrue(adapter.adapt(BINDING, partitioned, ACCOUNT, "dev").isEmpty());
    }

    private static Table csvTable(String location) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation(location);
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
        descriptor.setColumns(List.of(new Column("id", "int"), new Column("name", "string")));
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serde.setParameters(Map.of("field.delim", ",", "serialization.format", ","));
        descriptor.setSerdeInfo(serde);
        Table table = new Table();
        table.setName("events");
        table.setTableType("EXTERNAL_TABLE");
        table.setStorageDescriptor(descriptor);
        return table;
    }
}
