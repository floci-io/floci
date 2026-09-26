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

import java.util.ArrayList;
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
        return "SELECT floci_internal.purge_external_schema(" + literal(schemaName) + ")";
    }

    String refreshSql(ExternalSchemaBinding binding, List<Table> tables,
                      Map<String, List<Partition>> partitionsByTable) {
        List<Map<String, Object>> tableRows = new ArrayList<>();
        List<Map<String, Object>> columnRows = new ArrayList<>();
        List<Map<String, Object>> partitionRows = new ArrayList<>();
        for (Table table : tables) {
            StorageDescriptor descriptor = table.getStorageDescriptor();
            String serdeLibrary = serdeLibrary(descriptor);
            String serdeParameters = json(serdeParameters(descriptor));
            int compressed = descriptor != null && Boolean.TRUE.equals(descriptor.getCompressed()) ? 1 : 0;
            tableRows.add(row(
                    "tablename", table.getName(),
                    "tabletype", "VIRTUAL_VIEW".equals(table.getTableType()) ? "VIEW" : "TABLE",
                    "location", descriptor == null ? "" : descriptor.getLocation(),
                    "input_format", descriptor == null ? "" : descriptor.getInputFormat(),
                    "output_format", descriptor == null ? "" : descriptor.getOutputFormat(),
                    "serialization_lib", serdeLibrary,
                    "serde_parameters", serdeParameters,
                    "compressed", compressed,
                    "parameters", json(table.getParameters())));
            appendColumns(columnRows, table);
            List<Partition> partitions = partitionsByTable.get(table.getName());
            if (partitions != null) {
                for (Partition partition : partitions) {
                    appendPartition(partitionRows, table.getName(), partition, serdeLibrary, serdeParameters, compressed);
                }
            }
        }
        Map<String, Object> payload = row(
                "databasename", binding.glueDatabase(),
                "esoptions", json(Map.of("IAM_ROLE", binding.iamRoleArn())),
                "tables", tableRows,
                "columns", columnRows,
                "partitions", partitionRows);
        return "SELECT floci_internal.refresh_external_catalog(" + literal(binding.schemaName()) + ", "
                + literal(json(payload)) + "::jsonb)";
    }

    private static void appendColumns(List<Map<String, Object>> rows, Table table) {
        StorageDescriptor descriptor = table.getStorageDescriptor();
        List<Column> dataColumns = descriptor == null || descriptor.getColumns() == null
                ? List.of() : descriptor.getColumns();
        List<Column> partitionKeys = table.getPartitionKeys() == null ? List.of() : table.getPartitionKeys();
        int number = 1;
        for (Column column : dataColumns) {
            appendColumn(rows, table.getName(), column, number++, 0);
        }
        int partitionOrder = 1;
        for (Column column : partitionKeys) {
            appendColumn(rows, table.getName(), column, number++, partitionOrder++);
        }
    }

    private static void appendColumn(List<Map<String, Object>> rows, String tableName, Column column,
                                     int columnNumber, int partitionOrder) {
        rows.add(row("tablename", tableName,
                "columnname", column.getName(),
                "external_type", column.getType(),
                "columnnum", columnNumber,
                "part_key", partitionOrder,
                "is_nullable", "true"));
    }

    private void appendPartition(List<Map<String, Object>> rows, String tableName, Partition partition,
                                 String serdeLibrary, String serdeParameters, int compressed) {
        StorageDescriptor descriptor = partition.getStorageDescriptor();
        String values = jsonValues(partition.getValues());
        rows.add(row("tablename", tableName,
                "values", values,
                "location", descriptor == null ? "" : descriptor.getLocation(),
                "input_format", descriptor == null ? "" : descriptor.getInputFormat(),
                "output_format", descriptor == null ? "" : descriptor.getOutputFormat(),
                "serialization_lib", serdeLibrary,
                "serde_parameters", serdeParameters,
                "compressed", compressed,
                "parameters", json(partition.getParameters())));
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
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

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
