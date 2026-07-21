package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
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
     * SQL rewritten with positional {@code ?} placeholders, plus the ordered
     * list of the Data API parameter names each placeholder was derived from.
     * A name repeats once per occurrence in the original SQL.
     */
    record ParsedSql(String sql, List<String> parameterOrder) {
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?} without
     * treating backslash as a string-literal escape (the PostgreSQL default
     * with {@code standard_conforming_strings} on).
     */
    static ParsedSql parse(String sql) {
        return parse(sql, false);
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?}, skipping over
     * string literals, quoted/backtick identifiers, line and block comments,
     * PostgreSQL {@code ::} casts, and PostgreSQL dollar-quoted strings so a
     * colon inside any of those is left untouched.
     *
     * @param backslashEscapes when {@code true}, a backslash inside a single- or
     *        double-quoted string escapes the next character (MySQL/MariaDB
     *        default, i.e. {@code NO_BACKSLASH_ESCAPES} disabled). Backtick
     *        identifiers never honor backslash escaping.
     */
    static ParsedSql parse(String sql, boolean backslashEscapes) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> order = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = len;
                }
                out.append(sql, i, end);
                i = end;
                continue;
            }

            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? len : end + 2;
                out.append(sql, i, end);
                i = end;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                i = copyQuoted(sql, i, c, out, backslashEscapes);
                continue;
            }

            if (c == '$') {
                int consumed = copyDollarQuoted(sql, i, out);
                if (consumed > i) {
                    i = consumed;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == ':') {
                if (i + 1 < len && sql.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }
                if (i + 1 < len && isNameStart(sql.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < len && isNamePart(sql.charAt(j))) {
                        j++;
                    }
                    order.add(sql.substring(i + 1, j));
                    out.append('?');
                    i = j;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return new ParsedSql(out.toString(), order);
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
        String hint = typeHint == null ? "" : typeHint.toUpperCase();
        try {
            switch (hint) {
                case "DECIMAL" -> statement.setBigDecimal(index, new BigDecimal(value));
                case "TIMESTAMP" -> statement.setTimestamp(index, Timestamp.valueOf(value));
                case "DATE" -> statement.setDate(index, Date.valueOf(value));
                case "TIME" -> statement.setTime(index, Time.valueOf(value));
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

    private static byte[] blobBytes(String name, JsonNode blob) {
        try {
            return blob.binaryValue();
        } catch (IOException e) {
            throw new AwsException("BadRequestException",
                    "Parameter :" + name + " blobValue is not valid base64: " + e.getMessage(), 400);
        }
    }

    private static int copyQuoted(String sql, int start, char quote, StringBuilder out, boolean backslashEscapes) {
        int len = sql.length();
        boolean escapable = backslashEscapes && quote != '`';
        out.append(quote);
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            out.append(c);
            if (escapable && c == '\\' && i + 1 < len) {
                out.append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    out.append(quote);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int copyDollarQuoted(String sql, int start, StringBuilder out) {
        int len = sql.length();
        int tagEnd = start + 1;
        while (tagEnd < len && isNamePart(sql.charAt(tagEnd))) {
            tagEnd++;
        }
        if (tagEnd >= len || sql.charAt(tagEnd) != '$') {
            return start;
        }
        String tag = sql.substring(start, tagEnd + 1);
        int close = sql.indexOf(tag, tagEnd + 1);
        int end = close < 0 ? len : close + tag.length();
        out.append(sql, start, end);
        return end;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
