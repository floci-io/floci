package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.PostgresWire;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
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

    /**
     * A column of the replicated table: its Redshift type, its type on the source, and how a value of the source
     * type is read out, as an expression over an operand of that type.
     */
    record MappedColumn(String name, String redshiftType, String sourceType, UnaryOperator<String> reader) {

        /** The expression that reads the column out of the source table. */
        String selectExpression() {
            return reader.apply(PostgresWire.quoteIdentifier(name));
        }

        /**
         * The expression that turns one bound parameter, the source's text output of a value, into the value the
         * Redshift column takes: the same conversion {@link #selectExpression()} gives the initial load, whose CSV
         * also carries each value as its text.
         */
        String valueExpression() {
            return "CAST(CAST(" + reader.apply("CAST(? AS " + sourceType + ")") + " AS text) AS " + redshiftType + ")";
        }
    }

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
        if (!column.builtin()) {
            return Optional.empty();
        }
        String typeName = column.typeName();
        switch (typeName) {
            case "int2vector" -> {
                return mapped(column, SUPER, value -> "to_jsonb(" + value + "::int2[])");
            }
            case "oidvector" -> {
                return mapped(column, SUPER, value -> "to_jsonb(" + value + "::oid[])");
            }
            default -> { }
        }
        if ("A".equals(column.category())) {
            String formatted = column.formattedType();
            SourceColumn element = new SourceColumn(column.name(), column.elementTypeName(),
                    column.elementTypeType(), "", column.elementBuiltin(), null, null, false,
                    formatted.endsWith("[]") ? formatted.substring(0, formatted.length() - 2) : formatted);
            return column.elementTypeName() != null && map(element).isPresent()
                    ? mapped(column, SUPER, value -> "to_jsonb(" + value + ")") : Optional.empty();
        }
        if (!"b".equals(column.typeType())) {
            return Optional.empty();
        }
        String asIs = AS_IS.get(typeName);
        if (asIs != null) {
            return mapped(column, asIs, value -> value);
        }
        String asText = AS_TEXT.get(typeName);
        if (asText != null) {
            return mapped(column, asText, value -> value + "::text");
        }
        return switch (typeName) {
            case "json", "jsonb" -> mapped(column, SUPER, value -> value + "::jsonb");
            case "money" -> mapped(column, "DECIMAL(20,3)", value -> value + "::numeric");
            case "oid", "xid", "cid" -> mapped(column, "BIGINT", value -> value + "::text::bigint");
            case "xid8" -> mapped(column, "DECIMAL(20,0)", value -> value + "::text::numeric");
            case "numeric" -> numeric(column);
            case "bpchar" -> character(column);
            default -> Optional.empty();
        };
    }

    private static Optional<MappedColumn> numeric(SourceColumn column) {
        Matcher matcher = NUMERIC.matcher(column.formattedType());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int precision = Integer.parseInt(matcher.group(1));
        int scale = Integer.parseInt(matcher.group(2));
        if (precision > MAX_DECIMAL_PRECISION || scale < 0 || scale > precision) {
            return Optional.empty();
        }
        return mapped(column, "DECIMAL(" + precision + "," + scale + ")", value -> value);
    }

    private static Optional<MappedColumn> character(SourceColumn column) {
        Matcher matcher = CHARACTER.matcher(column.formattedType());
        if (!matcher.matches()) {
            // A bpchar without a length takes any length, so it is read as text.
            return mapped(column, LONG_VARCHAR, value -> value + "::text");
        }
        int length = Integer.parseInt(matcher.group(1));
        if (length > MAX_CHAR_LENGTH) {
            return Optional.empty();
        }
        return mapped(column, "CHAR(" + length + ")", value -> value);
    }

    private static Optional<MappedColumn> mapped(SourceColumn column, String redshiftType,
                                                 UnaryOperator<String> reader) {
        return Optional.of(new MappedColumn(column.name(), redshiftType, column.formattedType(), reader));
    }
}
