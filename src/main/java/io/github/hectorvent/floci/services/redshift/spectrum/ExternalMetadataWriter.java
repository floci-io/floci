package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors Glue metadata into the tables exposed by the SVV external catalog views. */
@ApplicationScoped
public class ExternalMetadataWriter {
    private static final Logger LOG = Logger.getLogger(ExternalMetadataWriter.class);
    private final GlueService glueService;
    private final ObjectMapper objectMapper;

    public ExternalMetadataWriter(GlueService glueService, ObjectMapper objectMapper) {
        this.glueService = glueService;
        this.objectMapper = objectMapper;
    }

    public void refresh(BackendSql backend, String accountId, ExternalSchemaBinding binding) {
        List<Table> tables = RequestScopes.callAs(accountId, () -> glueService.getTables(binding.glueDatabase()));
        Map<String, List<Partition>> partitions = new LinkedHashMap<>();
        for (Table table : tables) {
            partitions.put(table.getName(), RequestScopes.callAs(accountId,
                    () -> glueService.getPartitions(binding.glueDatabase(), table.getName())));
        }
        run(backend, refreshSql(binding, tables, partitions));
    }

    public void purge(BackendSql backend, String schemaName) {
        run(backend, purgeSql(schemaName));
    }

    private void run(BackendSql backend, String sql) {
        try {
            backend.execute(sql);
        } catch (SpectrumReadException exception) {
            if (!"42P01".equals(exception.sqlState())) {
                throw exception;
            }
            LOG.warnv("Spectrum metadata tables are missing; restart the cluster to re-run the catalog bootstrap: {0}",
                    exception.getMessage());
        }
    }

    String purgeSql(String schemaName) {
        String schema = literal(schemaName);
        return "DELETE FROM floci_internal.external_schemas WHERE schemaname = " + schema + ";\n"
                + "DELETE FROM floci_internal.external_tables WHERE schemaname = " + schema + ";\n"
                + "DELETE FROM floci_internal.external_columns WHERE schemaname = " + schema + ";\n"
                + "DELETE FROM floci_internal.external_partitions WHERE schemaname = " + schema + ";\n";
    }

    String refreshSql(ExternalSchemaBinding binding, List<Table> tables,
                      Map<String, List<Partition>> partitionsByTable) {
        String schema = literal(binding.schemaName());
        StringBuilder sql = new StringBuilder(purgeSql(binding.schemaName()));
        sql.append("INSERT INTO floci_internal.external_schemas (schemaname, databasename, esoptions) VALUES (")
                .append(schema).append(", ").append(literal(binding.glueDatabase())).append(", ")
                .append(literal(json(Map.of("IAM_ROLE", binding.iamRoleArn())))).append(");\n");
        for (Table table : tables) {
            StorageDescriptor descriptor = table.getStorageDescriptor();
            String serdeLibrary = serdeLibrary(descriptor);
            String serdeParameters = json(serdeParameters(descriptor));
            int compressed = descriptor != null && Boolean.TRUE.equals(descriptor.getCompressed()) ? 1 : 0;
            String tableName = literal(table.getName());
            sql.append("INSERT INTO floci_internal.external_tables (schemaname, tablename, tabletype, location, ")
                    .append("input_format, output_format, serialization_lib, serde_parameters, compressed, parameters) VALUES (")
                    .append(schema).append(", ").append(tableName).append(", ")
                    .append(literal("VIRTUAL_VIEW".equals(table.getTableType()) ? "VIEW" : "TABLE")).append(", ")
                    .append(literal(descriptor == null ? "" : descriptor.getLocation())).append(", ")
                    .append(literal(descriptor == null ? "" : descriptor.getInputFormat())).append(", ")
                    .append(literal(descriptor == null ? "" : descriptor.getOutputFormat())).append(", ")
                    .append(literal(serdeLibrary)).append(", ").append(literal(serdeParameters)).append(", ")
                    .append(compressed).append(", ").append(literal(json(table.getParameters()))).append(");\n");
            appendColumns(sql, schema, tableName, table);
            List<Partition> partitions = partitionsByTable.get(table.getName());
            if (partitions != null) {
                for (Partition partition : partitions) {
                    appendPartition(sql, schema, tableName, partition, serdeLibrary, serdeParameters, compressed);
                }
            }
        }
        return sql.toString();
    }

    private static void appendColumns(StringBuilder sql, String schema, String tableName, Table table) {
        StorageDescriptor descriptor = table.getStorageDescriptor();
        List<Column> dataColumns = descriptor == null || descriptor.getColumns() == null
                ? List.of() : descriptor.getColumns();
        List<Column> partitionKeys = table.getPartitionKeys() == null ? List.of() : table.getPartitionKeys();
        int number = 1;
        for (Column column : dataColumns) {
            appendColumn(sql, schema, tableName, column, number++, 0);
        }
        int partitionOrder = 1;
        for (Column column : partitionKeys) {
            appendColumn(sql, schema, tableName, column, number++, partitionOrder++);
        }
    }

    private static void appendColumn(StringBuilder sql, String schema, String tableName, Column column,
                                     int columnNumber, int partitionOrder) {
        sql.append("INSERT INTO floci_internal.external_columns (schemaname, tablename, columnname, external_type, ")
                .append("columnnum, part_key, is_nullable) VALUES (").append(schema).append(", ")
                .append(tableName).append(", ").append(literal(column.getName())).append(", ")
                .append(literal(column.getType())).append(", ").append(columnNumber).append(", ")
                .append(partitionOrder).append(", 'true');\n");
    }

    private void appendPartition(StringBuilder sql, String schema, String tableName, Partition partition,
                                 String serdeLibrary, String serdeParameters, int compressed) {
        StorageDescriptor descriptor = partition.getStorageDescriptor();
        String values = jsonValues(partition.getValues());
        sql.append("INSERT INTO floci_internal.external_partitions (schemaname, tablename, \"values\", location, ")
                .append("input_format, output_format, serialization_lib, serde_parameters, compressed, parameters) VALUES (")
                .append(schema).append(", ").append(tableName).append(", ").append(literal(values)).append(", ")
                .append(literal(descriptor == null ? "" : descriptor.getLocation())).append(", ")
                .append(literal(descriptor == null ? "" : descriptor.getInputFormat())).append(", ")
                .append(literal(descriptor == null ? "" : descriptor.getOutputFormat())).append(", ")
                .append(literal(serdeLibrary)).append(", ").append(literal(serdeParameters)).append(", ")
                .append(compressed).append(", ").append(literal(json(partition.getParameters()))).append(");\n");
    }

    private static String serdeLibrary(StorageDescriptor descriptor) {
        return descriptor == null || descriptor.getSerdeInfo() == null
                || descriptor.getSerdeInfo().getSerializationLibrary() == null
                ? "" : descriptor.getSerdeInfo().getSerializationLibrary();
    }

    private static Map<String, String> serdeParameters(StorageDescriptor descriptor) {
        return descriptor == null || descriptor.getSerdeInfo() == null
                ? Map.of() : descriptor.getSerdeInfo().getParameters();
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<String, String>() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize external table metadata", exception);
        }
    }

    private String jsonValues(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize external partition values", exception);
        }
    }

    private static String literal(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
