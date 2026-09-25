package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a Glue {@link Table} into the pieces of a DuckDB read plan: which {@code read_*}
 * function to use, the glob or Iceberg expression to read from, and the column projection that
 * exposes the catalog's declared names instead of the underlying files' inferred ones.
 *
 * <p>Extracted from {@code GlueViewDdlBuilder}, which was the only caller until Redshift Spectrum's
 * Glue-backed external tables needed the same table-to-read-plan logic outside Athena.
 */
public final class GlueTableResolver {

    /** Glue table property AWS/pyiceberg set to mark a table as Iceberg-format. */
    private static final String PARAM_TABLE_TYPE = "table_type";

    /** Glue table property holding the path to the table's current Iceberg metadata JSON. */
    private static final String PARAM_METADATA_LOCATION = "metadata_location";

    private static final String ICEBERG_TABLE_TYPE = "ICEBERG";

    /** Hive table property naming how many header lines a delimited file carries. */
    private static final String PARAM_SKIP_HEADER = "skip.header.line.count";

    private GlueTableResolver() {
    }

    public record ReadPlan(String fromClause, boolean iceberg, List<Column> columns) {
    }

    public static ReadPlan readPlan(Table table) {
        if (table == null || table.getStorageDescriptor() == null
                || table.getStorageDescriptor().getLocation() == null
                || table.getStorageDescriptor().getLocation().isBlank()) {
            throw new IllegalArgumentException("Glue table has no storage location");
        }
        List<Column> columns = declaredColumns(table);
        String metadata = isIcebergTable(table) ? icebergMetadataLocation(table) : null;
        if (metadata != null && !metadata.isBlank()) {
            return new ReadPlan(icebergReadExpression(metadata), true, columns);
        }
        String location = table.getStorageDescriptor().getLocation();
        String normalized = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        String readFunction = inferReadFunction(table);
        String path = readPath(table, normalized);
        if ("read_csv_auto".equals(readFunction) && hasCsvOptions(table)) {
            return new ReadPlan(csvReadExpression(table, path), false, columns);
        }
        return new ReadPlan(readExpression(readFunction, path), false, columns);
    }

    /**
     * A table that declares CSV options (header lines to skip, a delimiter) is read with exactly
     * those options and its declared columns rather than sniffed: a headerless file otherwise gets
     * its first row taken as the header and the declared column names bind to nothing.
     */
    static boolean hasCsvOptions(Table table) {
        Map<String, String> options = csvParameters(table);
        return options.containsKey(PARAM_SKIP_HEADER) || options.containsKey("field.delim")
                || options.containsKey("separatorChar");
    }

    static String csvReadExpression(Table table, String readPath) {
        Map<String, String> options = csvParameters(table);
        int skip = skipCount(options.get(PARAM_SKIP_HEADER));
        StringBuilder sql = new StringBuilder("read_csv('").append(escape(readPath)).append("/**'");
        sql.append(", header = ").append(skip == 1);
        if (skip > 1) {
            sql.append(", skip = ").append(skip);
        }
        String delimiter = options.containsKey("field.delim") ? options.get("field.delim") : options.get("separatorChar");
        sql.append(", delim = '").append(escape(delimiter == null || delimiter.isEmpty() ? "," : delimiter)).append("'");
        appendOption(sql, "quote", options.get("quoteChar"));
        appendOption(sql, "escape", options.get("escapeChar"));
        appendOption(sql, "nullstr", options.get("serialization.null.format"));
        List<Column> dataColumns = table.getStorageDescriptor().getColumns();
        if (dataColumns != null && !dataColumns.isEmpty()) {
            sql.append(", columns = {");
            for (int i = 0; i < dataColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('\'').append(escape(dataColumns.get(i).getName())).append("': 'VARCHAR'");
            }
            sql.append('}');
        }
        return sql.append(')').toString();
    }

    private static void appendOption(StringBuilder sql, String name, String value) {
        if (value != null && !value.isEmpty()) {
            sql.append(", ").append(name).append(" = '").append(escape(value)).append('\'');
        }
    }

    private static int skipCount(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException expected) {
            // an unparsable skip count is treated as absent, the same as a table that declares none
            return 0;
        }
    }

    /** Table, storage and SerDe parameters merged; the narrower scope wins. */
    private static Map<String, String> csvParameters(Table table) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (table.getParameters() != null) {
            merged.putAll(table.getParameters());
        }
        StorageDescriptor descriptor = table.getStorageDescriptor();
        if (descriptor != null) {
            if (descriptor.getParameters() != null) {
                merged.putAll(descriptor.getParameters());
            }
            if (descriptor.getSerdeInfo() != null && descriptor.getSerdeInfo().getParameters() != null) {
                merged.putAll(descriptor.getSerdeInfo().getParameters());
            }
        }
        return merged;
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    /**
     * Projects the columns the catalog declares, rather than whatever the underlying files spell.
     *
     * <p>Athena reports a table's declared column names: a JSON body written with {@code tenantId}
     * surfaces as {@code tenantid} when the table declares it that way, because the catalog owns the
     * schema and the SerDe maps onto it. Inference sees only the data, so without this the view
     * exposes the file's spelling and a client reading the declared name finds nothing. Identifier
     * resolution is case-insensitive here, so a declared name still binds to an inferred one that
     * differs from it only by case.
     *
     * <p>Partition keys are part of the table's schema and are what partition predicates filter on,
     * so they are projected too. A table that declares no columns keeps {@code *}, leaving inference
     * in charge.
     */
    public static String buildProjection(Table table) {
        List<Column> declared = declaredColumns(table);
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Column c : declared) {
            if (c == null || c.getName() == null || c.getName().isBlank()) {
                continue;
            }
            String name = c.getName();
            // A partition key repeating a data column would bind twice and make the view ambiguous.
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }

        if (names.isEmpty()) {
            return "*";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(names.get(i))).append(" AS ").append(quote(names.get(i)));
        }
        return sb.toString();
    }

    private static List<Column> declaredColumns(Table table) {
        List<Column> declared = new ArrayList<>();
        if (table != null) {
            if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
                declared.addAll(table.getStorageDescriptor().getColumns());
            }
            if (table.getPartitionKeys() != null) {
                declared.addAll(table.getPartitionKeys());
            }
        }
        List<Column> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Column column : declared) {
            if (column != null && column.getName() != null && !column.getName().isBlank()
                    && seen.add(column.getName().toLowerCase(Locale.ROOT))) {
                result.add(column);
            }
        }
        return result;
    }

    public static String inferReadFunction(Table table) {
        if (table == null || table.getStorageDescriptor() == null) {
            return "read_csv_auto";
        }
        String format = table.getStorageDescriptor().getInputFormat();
        String serde = table.getStorageDescriptor().getSerdeInfo() != null
                ? table.getStorageDescriptor().getSerdeInfo().getSerializationLibrary()
                : null;
        if (containsIgnoreCase(format, "parquet") || containsIgnoreCase(serde, "parquet")) {
            return "read_parquet";
        }
        if (containsIgnoreCase(format, "json") || containsIgnoreCase(serde, "json")
                || containsIgnoreCase(format, "hive")) {
            return "read_json_auto";
        }
        return "read_csv_auto";
    }

    public static String readExpression(String readFn, String normalizedLocation) {
        String escapedLocation = normalizedLocation.replace("'", "''");
        String glob = escapedLocation + "/**";
        if ("read_parquet".equals(readFn)) {
            return "read_parquet('" + glob + "', union_by_name = true)";
        }
        return readFn + "('" + glob + "')";
    }

    /**
     * The path to read for {@code table}, accounting for partition projection: see
     * {@link PartitionProjection#readPath(Table, String)}.
     */
    public static String readPath(Table table, String normalizedLocation) {
        return PartitionProjection.readPath(table, normalizedLocation);
    }

    /**
     * Iceberg tables are not read via a Hive {@code InputFormat}/{@code SerializationLibrary}
     * pair at all: pyiceberg and the AWS Glue-Iceberg integration leave those unset, so
     * {@link #inferReadFunction} would otherwise fall through to {@code read_csv_auto} and fail
     * on the table's binary Parquet data files, or a forced {@code read_parquet} would silently
     * glob every data file ever written under the table's location, including ones no longer
     * referenced by the current snapshot. Following the catalog's own {@code metadata_location}
     * into {@code iceberg_scan} instead resolves the table through its real manifest list.
     */
    public static boolean isIcebergTable(Table table) {
        return table != null
                && table.getParameters() != null
                && ICEBERG_TABLE_TYPE.equalsIgnoreCase(table.getParameters().get(PARAM_TABLE_TYPE));
    }

    public static String icebergMetadataLocation(Table table) {
        return table.getParameters().get(PARAM_METADATA_LOCATION);
    }

    public static String icebergReadExpression(String metadataLocation) {
        return "iceberg_scan('" + metadataLocation.replace("'", "''") + "')";
    }

    private static boolean containsIgnoreCase(String str, String sub) {
        return str != null && str.toLowerCase(Locale.ROOT).contains(sub);
    }

    private static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
