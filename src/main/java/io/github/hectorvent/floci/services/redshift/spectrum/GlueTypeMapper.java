package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps Glue column types to PostgreSQL types and DuckDB text projections. */
public final class GlueTypeMapper {
    private static final Pattern SIZED_STRING = Pattern.compile("(?:varchar|char)\\((\\d+)\\)");
    private static final Pattern DECIMAL = Pattern.compile("(?:decimal|numeric)(?:\\((\\d+)(?:,(\\d+))?\\))?");
    private static final String NULL_MARKER = "'\\N'";

    private GlueTypeMapper() {
    }

    public static String toPostgres(String glueType) {
        String type = normalize(glueType);
        Matcher sized = SIZED_STRING.matcher(type);
        if (sized.matches()) {
            return "varchar(" + sized.group(1) + ")";
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            if (decimal.group(1) == null) {
                return "numeric";
            }
            return "numeric(" + decimal.group(1) + "," + (decimal.group(2) == null ? "0" : decimal.group(2)) + ")";
        }
        if (isNested(type)) {
            return "jsonb";
        }
        return switch (type) {
            case "string", "varchar", "char" -> "text";
            case "tinyint", "smallint" -> "smallint";
            case "int", "integer" -> "integer";
            case "bigint" -> "bigint";
            case "float" -> "real";
            case "double" -> "double precision";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "timestamp" -> "timestamp";
            case "binary" -> "bytea";
            default -> throw new SpectrumSqlException("0A000", "Unsupported external column type: " + glueType);
        };
    }

    public static boolean isNested(String glueType) {
        String type = normalize(glueType);
        return type.startsWith("array<") || type.startsWith("map<") || type.startsWith("struct<");
    }

    public static String duckProjection(String columnName, String glueType) {
        String column = quote(columnName);
        String type = normalize(glueType);
        String expression = isNested(type) ? "to_json(" + column + ")"
                : "binary".equals(type) ? "'\\x' || hex(" + column + ")" : column;
        return "COALESCE(CAST(" + expression + " AS VARCHAR), " + NULL_MARKER + ") AS " + column;
    }

    private static String normalize(String glueType) {
        if (glueType == null) {
            throw new SpectrumSqlException("0A000", "External column type is missing");
        }
        return glueType.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
