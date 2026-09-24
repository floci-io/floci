package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Converts a strictly supported subset of Glue CSV metadata into the Phase 1 streaming descriptors. */
@ApplicationScoped
public final class SpectrumGlueCsvAdapter {

    private static final String TEXT_INPUT_FORMAT = "org.apache.hadoop.mapred.TextInputFormat";
    private static final String LAZY_SIMPLE_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";
    private static final String OPEN_CSV_SERDE = "org.apache.hadoop.hive.serde2.OpenCSVSerde";
    private static final Set<String> TABLE_PROPERTIES = Set.of("EXTERNAL", "skip.header.line.count", "field.delim",
            "serialization.format", "quoteChar", "escapeChar", "serialization.null.format");
    private static final Set<String> CSV_PROPERTIES = Set.of("field.delim", "serialization.format", "separatorChar",
            "quoteChar", "escapeChar", "serialization.null.format");

    public Optional<CsvTable> adapt(ExternalSchemaBinding binding, Table table,
                                   String accountId, String databaseName) {
        if (binding == null || table == null || accountId == null || databaseName == null
                || !"EXTERNAL_TABLE".equalsIgnoreCase(table.getTableType())
                || table.getPartitionKeys() != null && !table.getPartitionKeys().isEmpty()) {
            return Optional.empty();
        }
        StorageDescriptor descriptor = table.getStorageDescriptor();
        if (descriptor == null || descriptor.getLocation() == null || descriptor.getLocation().isBlank()
                || !isS3Location(descriptor.getLocation())
                || !TEXT_INPUT_FORMAT.equals(descriptor.getInputFormat())
                || Boolean.TRUE.equals(descriptor.getCompressed())
                || descriptor.getNumberOfBuckets() != null && descriptor.getNumberOfBuckets() > 0
                || descriptor.getSchemaReference() != null || !empty(descriptor.getParameters())) {
            return Optional.empty();
        }
        StorageDescriptor.SerDeInfo serde = descriptor.getSerdeInfo();
        if (serde == null || !isSupportedSerde(serde.getSerializationLibrary())) {
            return Optional.empty();
        }
        try {
            if (table.getParameters() != null) {
                for (String property : table.getParameters().keySet()) {
                    if (!TABLE_PROPERTIES.contains(property)) {
                        return Optional.empty();
                    }
                }
                String external = table.getParameters().get("EXTERNAL");
                if (external != null && !"TRUE".equalsIgnoreCase(external)) {
                    return Optional.empty();
                }
            }
            Map<String, String> parameters = supportedParameters(table, serde);
            CsvProperties properties = csvProperties(parameters);
            List<SpectrumColumn> columns = columns(descriptor.getColumns());
            SpectrumExternalSchema schema = new SpectrumExternalSchema(accountId, databaseName,
                    binding.schemaName(), descriptor.getLocation(), binding.iamRoleArn());
            SpectrumExternalTable externalTable = new SpectrumExternalTable(accountId, databaseName,
                    binding.schemaName(), table.getName(), columns, descriptor.getLocation(), properties.delimiter(),
                    properties.quote(), properties.escape(), properties.nullValue(), properties.headerLines());
            return Optional.of(new CsvTable(schema, externalTable));
        } catch (IllegalArgumentException | SpectrumSqlException exception) {
            return Optional.empty();
        }
    }

    private static boolean isS3Location(String location) {
        try {
            SpectrumExternalSchema.validateS3Location(location);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isSupportedSerde(String serializationLibrary) {
        return LAZY_SIMPLE_SERDE.equals(serializationLibrary) || OPEN_CSV_SERDE.equals(serializationLibrary);
    }

    private static boolean empty(Map<String, String> values) {
        return values == null || values.isEmpty();
    }

    private static Map<String, String> supportedParameters(Table table, StorageDescriptor.SerDeInfo serde) {
        Map<String, String> parameters = new LinkedHashMap<>();
        merge(parameters, serde.getParameters());
        if (table.getParameters() != null) {
            Map<String, String> tableCsv = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : table.getParameters().entrySet()) {
                if (!"EXTERNAL".equals(entry.getKey())) {
                    tableCsv.put(entry.getKey(), entry.getValue());
                }
            }
            merge(tableCsv, parameters);
            parameters = tableCsv;
        }
        return parameters;
    }

    private static void merge(Map<String, String> destination, Map<String, String> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!CSV_PROPERTIES.contains(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException("Unsupported Glue CSV property");
            }
            String existing = destination.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalArgumentException("Conflicting Glue CSV properties");
            }
        }
    }

    private static CsvProperties csvProperties(Map<String, String> values) {
        char delimiter = singleCharacter(values, "field.delim", ',');
        delimiter = singleCharacter(values, "serialization.format", delimiter);
        delimiter = singleCharacter(values, "separatorChar", delimiter);
        char quote = singleCharacter(values, "quoteChar", '"');
        char escape = singleCharacter(values, "escapeChar", '\\');
        String nullValue = values.getOrDefault("serialization.null.format", "\\N");
        String header = values.getOrDefault("skip.header.line.count", "0");
        int headerLines;
        try {
            headerLines = Integer.parseInt(header);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid CSV header count", exception);
        }
        if (headerLines < 0 || nullValue.isEmpty()) {
            throw new IllegalArgumentException("Invalid Glue CSV properties");
        }
        return new CsvProperties(delimiter, quote, escape, nullValue, headerLines);
    }

    private static char singleCharacter(Map<String, String> values, String key, char defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value.length() != 1) {
            throw new IllegalArgumentException("Glue CSV property must contain exactly one character");
        }
        return value.charAt(0);
    }

    private static List<SpectrumColumn> columns(List<Column> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("Glue table has no declared columns");
        }
        List<SpectrumColumn> columns = new ArrayList<>(source.size());
        for (Column column : source) {
            if (column == null || column.getName() == null || column.getType() == null) {
                throw new IllegalArgumentException("Glue table has an incomplete column");
            }
            String normalizedGlueType = column.getType().trim().toLowerCase(Locale.ROOT).replace(" ", "");
            String postgresType = GlueTypeMapper.toPostgres(column.getType());
            SpectrumColumn.Type legacyType = switch (normalizedGlueType.equals("char") ? "char"
                    : postgresType.toLowerCase(Locale.ROOT)) {
                case "text", "varchar" -> SpectrumColumn.Type.VARCHAR;
                case "char" -> SpectrumColumn.Type.CHAR;
                case "integer" -> SpectrumColumn.Type.INTEGER;
                case "bigint" -> SpectrumColumn.Type.BIGINT;
                case "numeric" -> SpectrumColumn.Type.DECIMAL;
                case "boolean" -> SpectrumColumn.Type.BOOLEAN;
                case "date" -> SpectrumColumn.Type.DATE;
                case "timestamp" -> SpectrumColumn.Type.TIMESTAMP;
                default -> throw new IllegalArgumentException("Glue type is not supported by Phase 1 CSV");
            };
            columns.add(new SpectrumColumn(column.getName(), legacyType));
        }
        return List.copyOf(columns);
    }

    private record CsvProperties(char delimiter, char quote, char escape, String nullValue, int headerLines) {
    }

    public record CsvTable(SpectrumExternalSchema schema, SpectrumExternalTable table) {
    }
}
