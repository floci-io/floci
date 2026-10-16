package io.github.hectorvent.floci.services.rds.export;

import java.util.Map;

/**
 * One column of an exported PostgreSQL table, named by its {@code pg_type.typname}. The Parquet
 * type follows the RDS export mapping: integers, floating point and boolean keep a Parquet type of
 * their own, {@code bytea} is binary, and every other type, arrays, {@code json}, {@code numeric},
 * dates and times, {@code uuid} and extension types such as {@code vector} included, is a UTF-8
 * string holding PostgreSQL's text output for the value.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ExportSnapshot.data-types.html">
 *     Data conversion when exporting to an Amazon S3 bucket</a>
 */
record ExportColumn(String name, String pgType) {

    private static final Map<String, String> NATIVE_DUCK_TYPES = Map.of(
            "int2", "SMALLINT",
            "int4", "INTEGER",
            "int8", "BIGINT",
            "float4", "FLOAT",
            "float8", "DOUBLE",
            "bool", "BOOLEAN");

    private static final Map<String, String> PARQUET_TYPE_NAMES = Map.of(
            "SMALLINT", "INT32",
            "INTEGER", "INT32",
            "BIGINT", "INT64",
            "FLOAT", "FLOAT",
            "DOUBLE", "DOUBLE",
            "BOOLEAN", "BOOLEAN",
            "BLOB", "BINARY");

    private static final String BYTEA = "bytea";

    /** The Parquet type, as the DuckDB type the column is written with. */
    String parquetType() {
        if (BYTEA.equals(pgType)) {
            return "BLOB";
        }
        return NATIVE_DUCK_TYPES.getOrDefault(pgType, "VARCHAR");
    }

    /** The Parquet primitive type the column is written as, or STRING for a UTF-8 string. */
    String parquetTypeName() {
        return PARQUET_TYPE_NAMES.getOrDefault(parquetType(), "STRING");
    }

    /** The type DuckDB reads the staged JSON value as, before it is written. */
    String stagedType() {
        return BYTEA.equals(pgType) ? "VARCHAR" : parquetType();
    }

    /** The PostgreSQL expression that stages the value: native, hex for bytea, text otherwise. */
    String extractExpression() {
        String column = quoteIdentifier(name);
        if (BYTEA.equals(pgType)) {
            return "encode(" + column + ", 'hex')";
        }
        if (NATIVE_DUCK_TYPES.containsKey(pgType)) {
            return column;
        }
        return column + "::text";
    }

    /** The DuckDB expression that writes the staged value. */
    String writeExpression() {
        String column = quoteIdentifier(name);
        if (BYTEA.equals(pgType)) {
            return "unhex(" + column + ") AS " + column;
        }
        return column;
    }

    /**
     * RDS skips a table with any of these characters in a column name.
     *
     * @see <a href="https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ExportSnapshot.html#USER_ExportSnapshot.Limits">
     *     Limitations</a>
     */
    boolean hasExportableName() {
        for (char c : name.toCharArray()) {
            if (",;{}()\n\t= ".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
