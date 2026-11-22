package io.floci.aws.services.redshift.spectrum;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The data types a Lambda UDF takes and returns, as the Redshift documentation lists them, and the PostgreSQL name
 * each maps to. Aliases collapse to one canonical name so two spellings of a signature are the same function.
 */
final class ExternalFunctionTypes {
    private static final Pattern MODIFIER = Pattern.compile("\\s*\\(\\s*(\\d+)(?:\\s*,\\s*(\\d+))?\\s*\\)\\s*$");
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("smallint", "smallint"), Map.entry("int2", "smallint"),
            Map.entry("integer", "integer"), Map.entry("int", "integer"), Map.entry("int4", "integer"),
            Map.entry("bigint", "bigint"), Map.entry("int8", "bigint"),
            Map.entry("decimal", "numeric"), Map.entry("numeric", "numeric"),
            Map.entry("real", "real"), Map.entry("float4", "real"),
            Map.entry("double precision", "double precision"), Map.entry("float8", "double precision"),
            Map.entry("float", "double precision"),
            Map.entry("char", "character"), Map.entry("character", "character"), Map.entry("nchar", "character"),
            Map.entry("bpchar", "character"),
            Map.entry("varchar", "character varying"), Map.entry("character varying", "character varying"),
            Map.entry("nvarchar", "character varying"), Map.entry("text", "character varying"),
            Map.entry("boolean", "boolean"), Map.entry("bool", "boolean"),
            Map.entry("date", "date"),
            Map.entry("timestamp", "timestamp"), Map.entry("timestamp without time zone", "timestamp"));

    private ExternalFunctionTypes() {
    }

    /** The canonical base name, with no length or precision; what identifies an argument in a signature. */
    static String base(String declared) {
        return resolve(declared).base();
    }

    /** The canonical name with the length or precision the declaration gave, for a return type. */
    static String declared(String declared) {
        Resolved type = resolve(declared);
        return type.modifier() == null ? type.base() : type.base() + type.modifier();
    }

    private record Resolved(String base, String modifier) { }

    private static Resolved resolve(String declared) {
        String text = declared.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String modifier = null;
        Matcher matcher = MODIFIER.matcher(text);
        if (matcher.find()) {
            modifier = "(" + matcher.group(1) + (matcher.group(2) == null ? "" : "," + matcher.group(2)) + ")";
            text = text.substring(0, matcher.start());
        }
        String base = CANONICAL.get(text);
        if (base == null) {
            throw new SpectrumSqlException("0A000", "Data type \"" + declared.trim()
                    + "\" is not supported for a Lambda UDF");
        }
        return new Resolved(base, modifier);
    }

    /** True when the text names a supported type, which tells an argument name from the start of a type. */
    static boolean isType(String declared) {
        try {
            resolve(declared);
            return true;
        } catch (SpectrumSqlException unsupported) {
            return false;
        }
    }
}
