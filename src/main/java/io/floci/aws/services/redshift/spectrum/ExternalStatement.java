package io.floci.aws.services.redshift.spectrum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface ExternalStatement permits ExternalStatement.CreateSchema, ExternalStatement.CreateKinesisSchema,
        ExternalStatement.CreateMskSchema, ExternalStatement.CreateTable, ExternalStatement.AddPartitions,
        ExternalStatement.DropSchema, ExternalStatement.DropTable, ExternalStatement.CreateStreamingView,
        ExternalStatement.RefreshView, ExternalStatement.DropView, ExternalStatement.CreateFunction,
        ExternalStatement.DropFunction, ExternalStatement.CreateIntegrationDatabase {
    /** Read-only copy that keeps statement order, so Glue payloads are stable across runs. */
    private static <V> Map<String, V> ordered(Map<String, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    record ColumnDefinition(String name, String type) { }
    enum TableFormat { PARQUET, TEXTFILE, JSON }
    record PartitionSpec(Map<String, String> values, String location) {
        public PartitionSpec { values = ordered(values); }
    }
    record CreateSchema(String schemaName, String glueDatabase, String iamRoleArn, boolean createDatabaseIfNotExists,
                          boolean ifNotExists) implements ExternalStatement { }
    /** {@code region} is null when the statement names none, which leaves the cluster's own Region. */
    record CreateKinesisSchema(String schemaName, String region, String iamRoleArn,
                               boolean ifNotExists) implements ExternalStatement { }
    record CreateMskSchema(String schemaName, String iamRoleArn, String authentication,
                           String clusterArn, boolean ifNotExists) implements ExternalStatement { }
    record CreateTable(String schemaName, String tableName, List<ColumnDefinition> columns,
                       List<ColumnDefinition> partitionColumns, TableFormat format, String location,
                       String delimiter, String serde, Map<String, String> properties,
                       Map<String, String> serdeProperties) implements ExternalStatement {
        public CreateTable {
            columns = List.copyOf(columns);
            partitionColumns = List.copyOf(partitionColumns);
            properties = ordered(properties);
            serdeProperties = ordered(serdeProperties);
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
    /**
     * {@code CREATE MATERIALIZED VIEW ... AS SELECT ...}; whether it reads a stream is for the schema bindings to
     * say. {@code viewSchema} is null when the name is unqualified. {@code select} is the query as written.
     */
    record CreateStreamingView(String viewSchema, String viewName, boolean autoRefresh,
                               String select) implements ExternalStatement { }
    record RefreshView(String viewSchema, String viewName) implements ExternalStatement { }
    record DropView(String viewSchema, String viewName, boolean ifExists, boolean cascade) implements ExternalStatement { }
    /**
     * {@code CREATE [OR REPLACE] EXTERNAL FUNCTION}. {@code schemaName} is null when the name is unqualified, and
     * {@code argumentTypes} are the canonical base type names, which is what identifies an overload.
     * {@code returnType} keeps its length or precision, and {@code volatility} is {@code STABLE} or {@code VOLATILE}.
     */
    record CreateFunction(String schemaName, String functionName, List<String> argumentTypes, String returnType,
                          String volatility, String lambdaFunction, String iamRoleArn, long retryTimeoutMillis,
                          int maxBatchRows, long maxBatchSizeBytes, boolean orReplace) implements ExternalStatement {
        public CreateFunction { argumentTypes = List.copyOf(argumentTypes); }
    }
    record DropFunction(String schemaName, String functionName, List<String> argumentTypes, boolean ifExists,
                        boolean cascade) implements ExternalStatement {
        public DropFunction { argumentTypes = List.copyOf(argumentTypes); }
    }
    /**
     * {@code CREATE DATABASE <name> FROM INTEGRATION '<id>' [DATABASE <source>]}. {@code sourceDatabase} is null
     * when the statement names none; the {@code SET} and {@code WITH} options are accepted and not kept.
     */
    record CreateIntegrationDatabase(String databaseName, String integrationId,
                                     String sourceDatabase) implements ExternalStatement { }
}
