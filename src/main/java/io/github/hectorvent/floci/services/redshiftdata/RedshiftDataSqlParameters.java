package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SqlParameterParser;
import io.github.hectorvent.floci.core.common.SqlParameterParser.ParsedSql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates Redshift Data API {@code SqlParameter} bindings into JDBC
 * {@link PreparedStatement} bindings.
 *
 * <p>The Data API uses named placeholders ({@code :name}); JDBC uses positional
 * {@code ?}. {@link #parse(String)} rewrites the SQL to positional form while
 * recording the placeholder order. Redshift Data API parameter values are always
 * strings on the wire, so {@link #bind} binds every placeholder with
 * {@link PreparedStatement#setString}; PostgreSQL coerces to the column type.
 */
final class RedshiftDataSqlParameters {

    private RedshiftDataSqlParameters() {
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?}, skipping over
     * string literals, quoted identifiers, line and block comments, PostgreSQL
     * {@code ::} casts, and dollar-quoted strings so a colon inside any of those
     * is left untouched. Backslash is not treated as a string-literal escape
     * (the PostgreSQL default with {@code standard_conforming_strings} on).
     */
    static ParsedSql parse(String sql) {
        return SqlParameterParser.parse(sql, SqlParameterParser.Options.REDSHIFT);
    }

    /**
     * Binds each positional placeholder from {@code order} with the matching
     * value in {@code valuesByName}.
     *
     * @throws AwsException if a placeholder has no supplied value
     */
    static void bind(PreparedStatement statement, List<String> order, Map<String, String> valuesByName)
            throws SQLException {
        for (int position = 0; position < order.size(); position++) {
            String name = order.get(position);
            if (!valuesByName.containsKey(name)) {
                throw new AwsException("ValidationException",
                        "SQL references parameter :" + name + " but no matching value was supplied.", 400);
            }
            String value = valuesByName.get(name);
            if (value == null) {
                statement.setNull(position + 1, Types.NULL);
            } else {
                statement.setString(position + 1, value);
            }
        }
    }

    /**
     * Reads a Data API {@code {name, value}} array into an insertion-ordered map.
     */
    static Map<String, String> parseParameters(JsonNode request, String field) {
        JsonNode array = request.get(field);
        if (array == null || array.isNull()) {
            return Map.of();
        }
        if (!array.isArray()) {
            throw new AwsException("ValidationException", field + " must be an array of {name, value} objects.", 400);
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (JsonNode parameter : array) {
            if (parameter == null || !parameter.isObject() || !parameter.hasNonNull("name")) {
                throw new AwsException("ValidationException", "Each parameter must be an object with a name.", 400);
            }
            String name = parameter.get("name").asText();
            JsonNode value = parameter.get("value");
            String text = value == null || value.isNull() ? null : value.asText();
            if (byName.putIfAbsent(name, text) != null) {
                throw new AwsException("ValidationException", "Duplicate parameter name :" + name + ".", 400);
            }
        }
        return byName;
    }

    /**
     * Whether {@code sql} holds more than one statement, applying the same
     * literal / identifier / comment / dollar-quote skipping as {@link #parse}
     * so a {@code ;} inside any of those is not counted. Trailing {@code ;}
     * characters (with only whitespace after) are permitted.
     */
    static boolean isMultiStatement(String sql) {
        return SqlParameterParser.isMultiStatement(sql, SqlParameterParser.Options.REDSHIFT);
    }
}
