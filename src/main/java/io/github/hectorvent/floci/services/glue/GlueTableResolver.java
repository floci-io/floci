package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private GlueTableResolver() {
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
        List<Column> declared = new ArrayList<>();
        if (table != null) {
            if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
                declared.addAll(table.getStorageDescriptor().getColumns());
            }
            if (table.getPartitionKeys() != null) {
                declared.addAll(table.getPartitionKeys());
            }
        }

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
