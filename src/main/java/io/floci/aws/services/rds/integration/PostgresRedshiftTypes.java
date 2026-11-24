package io.floci.aws.services.rds.integration;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Amazon Redshift type a zero-ETL integration gives a PostgreSQL column, following the table
 * AWS publishes for Aurora PostgreSQL sources, and the expression that reads the column out of the
 * source in a form the Redshift column takes from CSV.
 *
 * <p>A type AWS does not replicate maps to nothing: an enum, a domain, a composite, a range, a type
 * an extension or a user created, an array of any of those, and a {@code numeric} without its
 * precision. A table with such a column is not replicated.
 */
final class PostgresRedshiftTypes {

    /** A source column as the catalog describes it. {@code element*} describe an array's element type. */
    record SourceColumn(String name, String typeName, String typeType, String category, boolean builtin,
                        String elementTypeName, String elementTypeType, boolean elementBuiltin,
                        String formattedType) {}

    /** A column of the replicated table: its Redshift type and how the source column is read out. */
    record MappedColumn(String name, String redshiftType, String selectExpression) {}

    private static final String SUPER = "pg_catalog.super";
    private static final String VARBYTE = "pg_catalog.varbyte";
    private static final String LONG_VARCHAR = "VARCHAR(65535)";
    private static final int MAX_DECIMAL_PRECISION = 38;
    private static final int MAX_CHAR_LENGTH = 65535;
    private static final Pattern NUMERIC = Pattern.compile("numeric\\((\\d+),(-?\\d+)\\)");
    private static final Pattern CHARACTER = Pattern.compile("character\\((\\d+)\\)");

    /** Types read out as they are, by {@code pg_type.typname}. */
    private static final Map<String, String> AS_IS = Map.ofEntries(
            Map.entry("int2", "SMALLINT"),
            Map.entry("int4", "INTEGER"),
            Map.entry("int8", "BIGINT"),
            Map.entry("bool", "BOOLEAN"),
            Map.entry("float4", "REAL"),
            Map.entry("float8", "DOUBLE PRECISION"),
            Map.entry("date", "DATE"),
            Map.entry("timestamp", "TIMESTAMP"),
            Map.entry("timestamptz", "TIMESTAMPTZ"),
            Map.entry("interval", "INTERVAL"),
            Map.entry("bytea", VARBYTE));

    /** Types read out as their text, by {@code pg_type.typname}. */
    private static final Map<String, String> AS_TEXT = Map.ofEntries(
            Map.entry("text", LONG_VARCHAR),
            Map.entry("varchar", LONG_VARCHAR),
            Map.entry("name", "VARCHAR(64)"),
            Map.entry("uuid", "VARCHAR(36)"),
            Map.entry("time", "VARCHAR(19)"),
            Map.entry("timetz", "VARCHAR(22)"),
            Map.entry("inet", "VARCHAR(19)"),
            Map.entry("cidr", "VARCHAR(19)"),
            Map.entry("macaddr", "VARCHAR(17)"),
            Map.entry("macaddr8", "VARCHAR(23)"),
            Map.entry("pg_lsn", "VARCHAR(17)"),
            Map.entry("tid", "VARCHAR(23)"),
            Map.entry("xml", LONG_VARCHAR),
            Map.entry("jsonpath", LONG_VARCHAR),
            Map.entry("tsvector", LONG_VARCHAR),
            Map.entry("tsquery", LONG_VARCHAR),
            Map.entry("gtsvector", LONG_VARCHAR),
            Map.entry("refcursor", LONG_VARCHAR),
            Map.entry("txid_snapshot", LONG_VARCHAR),
            Map.entry("pg_snapshot", LONG_VARCHAR),
            Map.entry("pg_node_tree", LONG_VARCHAR),
            Map.entry("pg_ndistinct", LONG_VARCHAR),
            Map.entry("pg_dependencies", LONG_VARCHAR),
            Map.entry("pg_mcv_list", LONG_VARCHAR),
            Map.entry("pg_brin_bloom_summary", LONG_VARCHAR));

    private PostgresRedshiftTypes() {}

    static Optional<MappedColumn> map(SourceColumn column) {
        String quoted = quoteIdentifier(column.name());
        if (!column.builtin()) {
            return Optional.empty();
        }
        String typeName = column.typeName();
        switch (typeName) {
            case "int2vector" -> {
                return mapped(column, SUPER, "to_jsonb(" + quoted + "::int2[])");
            }
            case "oidvector" -> {
                return mapped(column, SUPER, "to_jsonb(" + quoted + "::oid[])");
            }
            default -> { }
        }
        if ("A".equals(column.category())) {
            String formatted = column.formattedType();
            SourceColumn element = new SourceColumn(column.name(), column.elementTypeName(),
                    column.elementTypeType(), "", column.elementBuiltin(), null, null, false,
                    formatted.endsWith("[]") ? formatted.substring(0, formatted.length() - 2) : formatted);
            return column.elementTypeName() != null && map(element).isPresent()
                    ? mapped(column, SUPER, "to_jsonb(" + quoted + ")") : Optional.empty();
        }
        if (!"b".equals(column.typeType())) {
            return Optional.empty();
        }
        String asIs = AS_IS.get(typeName);
        if (asIs != null) {
            return mapped(column, asIs, quoted);
        }
        String asText = AS_TEXT.get(typeName);
        if (asText != null) {
            return mapped(column, asText, quoted + "::text");
        }
        return switch (typeName) {
            case "json", "jsonb" -> mapped(column, SUPER, quoted + "::jsonb");
            case "money" -> mapped(column, "DECIMAL(20,3)", quoted + "::numeric");
            case "oid", "xid", "cid" -> mapped(column, "BIGINT", quoted + "::text::bigint");
            case "xid8" -> mapped(column, "DECIMAL(20,0)", quoted + "::text::numeric");
            case "numeric" -> numeric(column, quoted);
            case "bpchar" -> character(column, quoted);
            default -> Optional.empty();
        };
    }

    private static Optional<MappedColumn> numeric(SourceColumn column, String quoted) {
        Matcher matcher = NUMERIC.matcher(column.formattedType());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int precision = Integer.parseInt(matcher.group(1));
        int scale = Integer.parseInt(matcher.group(2));
        if (precision > MAX_DECIMAL_PRECISION || scale < 0 || scale > precision) {
            return Optional.empty();
        }
        return mapped(column, "DECIMAL(" + precision + "," + scale + ")", quoted);
    }

    private static Optional<MappedColumn> character(SourceColumn column, String quoted) {
        Matcher matcher = CHARACTER.matcher(column.formattedType());
        int length = matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
        if (length > MAX_CHAR_LENGTH) {
            return Optional.empty();
        }
        return mapped(column, "CHAR(" + length + ")", quoted);
    }

    private static Optional<MappedColumn> mapped(SourceColumn column, String redshiftType, String expression) {
        return Optional.of(new MappedColumn(column.name(), redshiftType, expression));
    }

    static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
