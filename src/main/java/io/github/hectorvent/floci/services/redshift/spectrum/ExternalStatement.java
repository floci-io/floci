package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.List;
import java.util.Map;

public sealed interface ExternalStatement permits ExternalStatement.CreateSchema, ExternalStatement.CreateTable,
        ExternalStatement.AddPartitions, ExternalStatement.DropSchema, ExternalStatement.DropTable {
    record ColumnDefinition(String name, String type) { }
    enum TableFormat { PARQUET, TEXTFILE, JSON }
    record PartitionSpec(Map<String, String> values, String location) {
        public PartitionSpec { values = Map.copyOf(values); }
    }
    record CreateSchema(String schemaName, String glueDatabase, String iamRoleArn, boolean createDatabaseIfNotExists) implements ExternalStatement { }
    record CreateTable(String schemaName, String tableName, List<ColumnDefinition> columns,
                       List<ColumnDefinition> partitionColumns, TableFormat format, String location,
                       String delimiter, String serde, Map<String, String> properties,
                       Map<String, String> serdeProperties) implements ExternalStatement {
        public CreateTable {
            columns = List.copyOf(columns);
            partitionColumns = List.copyOf(partitionColumns);
            properties = Map.copyOf(properties);
            serdeProperties = Map.copyOf(serdeProperties);
        }

        public CreateTable(String schemaName, String tableName, List<ColumnDefinition> columns,
                           List<ColumnDefinition> partitionColumns, TableFormat format, String location,
                           String delimiter, String serde, Map<String, String> properties) {
            this(schemaName, tableName, columns, partitionColumns, format, location, delimiter, serde, properties, Map.of());
        }
    }
    record AddPartitions(String schemaName, String tableName, boolean ifNotExists,
                         List<PartitionSpec> partitions) implements ExternalStatement {
        public AddPartitions { partitions = List.copyOf(partitions); }
    }
    /** {@code cascade} is true only when the statement said CASCADE; RESTRICT is the default, as in PostgreSQL and Redshift. */
    record DropSchema(String schemaName, boolean ifExists, boolean cascade) implements ExternalStatement { }
    record DropTable(String schemaName, String tableName, boolean ifExists, boolean cascade) implements ExternalStatement { }
}
