package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SqlParameterParser;
import io.github.hectorvent.floci.core.common.SqlParameterParser.ParsedSql;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Translates AWS RDS Data API {@code SqlParameter} bindings into JDBC
 * {@link PreparedStatement} bindings.
 *
 * <p>The Data API uses named placeholders ({@code :name}); JDBC uses positional
 * {@code ?}. {@link #parse(String)} rewrites the SQL to positional form while
 * recording the placeholder order, and {@link #bind} binds each value variant.
 * The rewrite is shared across MySQL, MariaDB, and PostgreSQL; the only
 * dialect difference is whether a backslash escapes quotes inside string
 * literals (see {@link #parse(String, boolean)}).
 */
final class RdsDataSqlParameters {

    private RdsDataSqlParameters() {
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?} without
     * treating backslash as a string-literal escape (the PostgreSQL default
     * with {@code standard_conforming_strings} on).
     */
    static ParsedSql parse(String sql) {
        return SqlParameterParser.parse(sql);
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?}, skipping over
     * string literals, quoted/backtick identifiers, line and block comments,
     * PostgreSQL {@code ::} casts, and PostgreSQL dollar-quoted strings so a
     * colon inside any of those is left untouched.
     */
    static ParsedSql parse(String sql, boolean backslashEscapes) {
        return SqlParameterParser.parse(sql, backslashEscapes);
    }

    /**
     * Binds each positional placeholder from {@code order} using the matching
     * {@code SqlParameter} node in {@code parametersByName}.
     *
     * @throws AwsException if a placeholder has no matching parameter value
     */
    static void bind(PreparedStatement statement, List<String> order, Map<String, JsonNode> parametersByName)
            throws SQLException {
        for (int position = 0; position < order.size(); position++) {
            String name = order.get(position);
            JsonNode parameter = parametersByName.get(name);
            if (parameter == null) {
                throw new AwsException("BadRequestException",
                        "SQL statement references parameter :" + name
                                + " but no matching value was supplied.", 400);
            }
            bindValue(statement, position + 1, name, parameter);
        }
    }

    private static void bindValue(PreparedStatement statement, int index, String name, JsonNode parameter)
            throws SQLException {
        JsonNode value = parameter.get("value");
        if (value == null || value.isNull() || value.path("isNull").asBoolean(false)) {
            statement.setNull(index, Types.NULL);
            return;
        }
        String typeHint = text(parameter, "typeHint");

        if (value.has("booleanValue")) {
            statement.setBoolean(index, value.get("booleanValue").asBoolean());
        } else if (value.has("longValue")) {
            statement.setLong(index, value.get("longValue").asLong());
        } else if (value.has("doubleValue")) {
            statement.setDouble(index, value.get("doubleValue").asDouble());
        } else if (value.has("blobValue")) {
            statement.setBytes(index, blobBytes(name, value.get("blobValue")));
        } else if (value.has("arrayValue")) {
            throw new AwsException("BadRequestException",
                    "arrayValue is not supported for parameter :" + name
                            + " by this local RDS Data API implementation.", 400);
        } else if (value.has("stringValue")) {
            bindString(statement, index, name, value.get("stringValue").asText(), typeHint);
        } else {
            throw new AwsException("BadRequestException",
                    "Parameter :" + name + " has no supported value field.", 400);
        }
    }

    private static void bindString(PreparedStatement statement, int index, String name, String value, String typeHint)
            throws SQLException {
        String hint = typeHint == null ? "" : typeHint.toUpperCase(Locale.ROOT);
        try {
            switch (hint) {
                case "DECIMAL" -> statement.setBigDecimal(index, new BigDecimal(value));
                case "TIMESTAMP" -> statement.setTimestamp(index, Timestamp.valueOf(value));
                case "DATE" -> statement.setDate(index, Date.valueOf(value));
                case "TIME" -> statement.setTime(index, Time.valueOf(withoutFractionalSeconds(value)));
                case "UUID" -> statement.setObject(index, UUID.fromString(value));
                case "JSON" -> statement.setObject(index, value, Types.OTHER);
                default -> statement.setString(index, value);
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException",
                    "Parameter :" + name + " value \"" + value + "\" is not a valid "
                            + hint + " for the supplied typeHint.", 400);
        }
    }

    /**
     * The AWS RDS Data API documents {@code TIME} as {@code HH:MM:SS[.FFF]} with
     * optional fractional seconds, but {@link Time#valueOf(String)} only accepts
     * {@code HH:mm:ss} and throws on a fractional part. Drop any fractional
     * seconds so a documented-valid value is not falsely rejected.
     */
    private static String withoutFractionalSeconds(String value) {
        int dot = value.indexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }

    private static byte[] blobBytes(String name, JsonNode blob) {
        try {
            return blob.binaryValue();
        } catch (IOException e) {
            throw new AwsException("BadRequestException",
                    "Parameter :" + name + " blobValue is not valid base64: " + e.getMessage(), 400);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
