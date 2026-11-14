package io.floci.aws.services.glue;

import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /**
     * The column a templated table's read adds to carry each row's object path, from which its
     * partition values are taken. Named after Athena's own {@code "$path"} pseudo-column, a name no
     * data column takes; the view's explicit projection leaves it out.
     */
    private static final String OBJECT_PATH_COLUMN = "$path";

    /** The largest group index DuckDB's scalar {@code regexp_extract} accepts. */
    private static final int MAX_SCALAR_GROUP = 9;

    private static final Pattern DECIMAL_TYPE = Pattern.compile("decimal\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)");

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
     * Read plan constrained to the exact objects that the caller listed and authorized. This is
     * used by Redshift Spectrum, where a location-derived glob could match keys outside the
     * literal S3 prefix that was checked against the bound role.
     */
    public static ReadPlan readPlan(Table table, List<String> objectUris) {
        if (isIcebergTable(table)) {
            return readPlan(table);
        }
        if (objectUris == null || objectUris.isEmpty()) {
            throw new IllegalArgumentException("An exact-object read plan requires at least one object");
        }
        List<Column> columns = declaredColumns(table);
        String fileList = "[" + objectUris.stream().map(GlueTableResolver::sqlLiteral)
                .collect(Collectors.joining(", ")) + "]";
        String readFunction = inferReadFunction(table);
        String fromClause;
        if ("read_csv_auto".equals(readFunction) && hasCsvOptions(table)) {
            fromClause = csvReadExpressionForInput(table, fileList);
        } else if ("read_parquet".equals(readFunction)) {
            fromClause = "read_parquet(" + fileList + ", union_by_name = true)";
        } else {
            fromClause = readFunction + "(" + fileList + ")";
        }
        return new ReadPlan(fromClause, false, columns);
    }

    /** Whether {@link #readPlan(Table, List)} reads this table's objects with {@code read_parquet}. */
    public static boolean readsParquet(Table table) {
        return !isIcebergTable(table) && "read_parquet".equals(inferReadFunction(table));
    }

    /**
     * Lists the field names each given Parquet object carries, one row per object and field, nested
     * fields and the schema root included. The object is named as it was passed in.
     */
    public static String parquetSchemaSql(List<String> objectUris) {
        String fileList = "[" + objectUris.stream().map(GlueTableResolver::sqlLiteral)
                .collect(Collectors.joining(", ")) + "]";
        return "SELECT file_name, name FROM parquet_schema(" + fileList + ")";
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
        return csvReadExpressionForInput(table, "'" + escape(readPath) + "/**'");
    }

    private static String csvReadExpressionForInput(Table table, String inputExpression) {
        Map<String, String> options = csvParameters(table);
        int skip = skipCount(options.get(PARAM_SKIP_HEADER));
        StringBuilder sql = new StringBuilder("read_csv(").append(inputExpression);
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
            // Every column is read as VARCHAR: DuckDB's own type sniffing would disagree with the Glue types,
            // and the caller casts each column to its declared type afterwards.
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

    private static String sqlLiteral(String value) {
        String globLiteral = value.replace("[", "[[]").replace("*", "[*]").replace("?", "[?]");
        return "'" + globLiteral.replace("'", "''") + "'";
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
     *
     * <p>Partition values are not stored in the data files. In the default {@code column=value}
     * layout DuckDB's Hive partitioning supplies them by name; with a {@code storage.location.template}
     * each one is taken from the object's path instead, as Athena does, and cast to the key's type.
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

        PartitionProjection.LocationTemplate template = locationTemplate(table);
        Map<String, Column> templatedKeys = templatedKeys(table, template);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String name = names.get(i);
            Column key = templatedKeys.get(name.toLowerCase(Locale.ROOT));
            String value = key == null ? quote(name) : partitionValue(template, key);
            sb.append(value).append(" AS ").append(quote(name));
        }
        return sb.toString();
    }

    /**
     * The condition a templated table's view keeps rows by, or {@code null} for any other table: that
     * the object's path is one Athena could address through the template.
     *
     * <p>Athena never lists a projecting table's location; it builds each partition's path from
     * values and reads what is there. An object is therefore visible only if some values produce its
     * path: the path matches the template, a repeated placeholder holds the same value at each
     * occurrence, and each typed key's value parses as that type. The read glob is wider on all three
     * counts, so the view narrows it back.
     *
     * <p>TODO: Athena also reads only the values the projection generates ("queries for values that
     * are beyond the range bounds defined for partition projection ... [return] zero rows"), so an
     * object at a date before the range, after {@code NOW}, off the {@code interval}, an integer
     * without its {@code digits} padding, or an {@code enum} value not listed is invisible to it but
     * read here. The default {@code column=value} layout reads them too; filtering on the projection's
     * values would fix both.
     */
    public static String pathFilter(Table table) {
        PartitionProjection.LocationTemplate template = locationTemplate(table);
        if (template == null) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        terms.add("regexp_matches(" + quote(OBJECT_PATH_COLUMN) + ", '" + escape(template.regex()) + "')");
        for (Column key : templatedKeys(table, template).values()) {
            List<Integer> groups = template.groups().get(key.getName().toLowerCase(Locale.ROOT));
            for (int occurrence = 1; occurrence < groups.size(); occurrence++) {
                terms.add(pathSegment(template, groups.getFirst()) + " = "
                        + pathSegment(template, groups.get(occurrence)));
            }
            if (!"VARCHAR".equals(partitionValueType(key.getType()))) {
                terms.add(partitionValue(template, key) + " IS NOT NULL");
            }
        }
        return String.join(" AND ", terms);
    }

    /**
     * The table's location template, for a table read through {@code read_*}. An Iceberg table is
     * resolved through its own metadata, which a template plays no part in.
     */
    private static PartitionProjection.LocationTemplate locationTemplate(Table table) {
        return isIcebergTable(table) ? null : PartitionProjection.locationTemplate(table);
    }

    /** The partition keys {@code template} names, by lowercased name; empty without a template. */
    private static Map<String, Column> templatedKeys(Table table, PartitionProjection.LocationTemplate template) {
        Map<String, Column> keys = new LinkedHashMap<>();
        if (template == null) {
            return keys;
        }
        for (Column key : table.getPartitionKeys()) {
            if (key != null && key.getName() != null
                    && template.groups().containsKey(key.getName().toLowerCase(Locale.ROOT))) {
                keys.putIfAbsent(key.getName().toLowerCase(Locale.ROOT), key);
            }
        }
        return keys;
    }

    /**
     * The text one placeholder matched in the row's object path.
     *
     * <p>DuckDB's scalar {@code regexp_extract} takes a group index from 0 to 9 only, and a view using
     * a larger one fails to bind, which fails every view in the batch. A later group, as in a
     * template with ten or more placeholders (repeats included), is read through the struct form
     * instead, which names the groups up to it and returns each as a field. The scalar form stays
     * for the rest, keeping the common view short.
     */
    private static String pathSegment(PartitionProjection.LocationTemplate template, int group) {
        String extract = "regexp_extract(" + quote(OBJECT_PATH_COLUMN) + ", '" + escape(template.regex()) + "', ";
        if (group <= MAX_SCALAR_GROUP) {
            return extract + group + ")";
        }
        StringBuilder names = new StringBuilder("[");
        for (int i = 1; i <= group; i++) {
            names.append(i > 1 ? ", " : "").append("'g").append(i).append('\'');
        }
        return extract + names + "]).g" + group;
    }

    /**
     * {@code key}'s value for the row: its first placeholder's text, cast to the key's type. The cast
     * is a {@code TRY_CAST} so that an object whose path holds no such value is dropped by
     * {@link #pathFilter} rather than failing the whole query.
     */
    private static String partitionValue(PartitionProjection.LocationTemplate template, Column key) {
        String text = pathSegment(template, template.groups().get(key.getName().toLowerCase(Locale.ROOT)).getFirst());
        String type = partitionValueType(key.getType());
        return "VARCHAR".equals(type) ? text : "TRY_CAST(" + text + " AS " + type + ")";
    }

    /**
     * The DuckDB type a partition value is cast to: the scalar Hive types a partition key can take,
     * and {@code VARCHAR} for the string types and anything else.
     *
     * <p>TODO: a {@code date} or {@code timestamp} key projected with a {@code projection.<key>.format}
     * other than ISO ({@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm:ss}) needs {@code strptime} with the
     * Java pattern translated; until then its values do not parse and its objects are not read.
     */
    private static String partitionValueType(String glueType) {
        String type = glueType == null ? "" : glueType.trim().toLowerCase(Locale.ROOT);
        if (DECIMAL_TYPE.matcher(type).matches()) {
            return type.toUpperCase(Locale.ROOT);
        }
        return switch (type) {
            case "tinyint" -> "TINYINT";
            case "smallint" -> "SMALLINT";
            case "int", "integer" -> "INTEGER";
            case "bigint" -> "BIGINT";
            case "float" -> "FLOAT";
            case "double" -> "DOUBLE";
            case "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "timestamp" -> "TIMESTAMP";
            default -> "VARCHAR";
        };
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
        return readExpression(readFn, normalizedLocation, "");
    }

    /**
     * {@link #readExpression(String, String)} for {@code table}. A table with a location template
     * also reads each row's object path, which {@link #buildProjection} takes its partition values
     * from. Hive partitioning is switched off for it: the template is the only source of those
     * values, and a {@code col=value} segment it happens to contain would otherwise be read as a
     * second, untyped column.
     */
    public static String readExpression(Table table, String readFn, String normalizedLocation) {
        if (locationTemplate(table) == null) {
            return readExpression(readFn, normalizedLocation);
        }
        return readExpression(readFn, normalizedLocation,
                ", hive_partitioning = false, filename = '" + escape(OBJECT_PATH_COLUMN) + "'");
    }

    private static String readExpression(String readFn, String normalizedLocation, String options) {
        String glob = escape(normalizedLocation) + "/**";
        String unionByName = "read_parquet".equals(readFn) ? ", union_by_name = true" : "";
        return readFn + "('" + glob + "'" + unionByName + options + ")";
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
