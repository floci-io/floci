package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GlueTableBuilder {
    private GlueTableBuilder() { }
    public static Table toGlueTable(ExternalStatement.CreateTable statement) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation(statement.location());
        descriptor.setColumns(columns(statement.columns()));
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        Map<String, String> serdeParameters = new LinkedHashMap<>();
        switch (statement.format()) {
            case PARQUET -> { descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"); descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat"); serde.setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"); }
            case JSON -> { descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat"); descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"); serde.setSerializationLibrary(statement.serde() == null ? "org.openx.data.jsonserde.JsonSerDe" : statement.serde()); }
            case TEXTFILE -> { descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat"); descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"); serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"); String delimiter = statement.delimiter() == null ? "," : statement.delimiter(); serdeParameters.put("field.delim", delimiter); serdeParameters.put("serialization.format", delimiter); }
        }
        serde.setParameters(serdeParameters);
        descriptor.setSerdeInfo(serde);
        Table table = new Table();
        table.setName(statement.tableName()); table.setTableType("EXTERNAL_TABLE"); table.setStorageDescriptor(descriptor); table.setPartitionKeys(columns(statement.partitionColumns()));
        Map<String, String> parameters = new LinkedHashMap<>(statement.properties()); parameters.put("EXTERNAL", "TRUE"); table.setParameters(parameters);
        return table;
    }
    public static Partition toGluePartition(Table table, ExternalStatement.PartitionSpec spec) {
        List<String> values = new ArrayList<>();
        for (Column key : table.getPartitionKeys()) {
            String value = spec.values().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key.getName())).map(Map.Entry::getValue).findFirst().orElse(null);
            if (value == null) throw new SpectrumSqlException("22023", "partition key \"" + key.getName() + "\" is missing");
            values.add(value);
        }
        StorageDescriptor source = table.getStorageDescriptor();
        StorageDescriptor descriptor = new StorageDescriptor(); descriptor.setColumns(source.getColumns()); descriptor.setInputFormat(source.getInputFormat()); descriptor.setOutputFormat(source.getOutputFormat()); descriptor.setSerdeInfo(source.getSerdeInfo()); descriptor.setLocation(spec.location());
        Partition partition = new Partition(); partition.setValues(values); partition.setStorageDescriptor(descriptor); return partition;
    }
    private static List<Column> columns(List<ExternalStatement.ColumnDefinition> definitions) {
        List<Column> result = new ArrayList<>();
        for (ExternalStatement.ColumnDefinition definition : definitions) { Column column = new Column(); column.setName(definition.name()); column.setType(definition.type().toLowerCase(Locale.ROOT)); result.add(column); }
        return result;
    }
}
