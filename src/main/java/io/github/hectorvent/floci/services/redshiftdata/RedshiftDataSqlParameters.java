package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
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

    record ParsedSql(String sql, List<String> parameterOrder) {
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?}, skipping over
     * string literals, quoted identifiers, line and block comments, PostgreSQL
     * {@code ::} casts, and dollar-quoted strings so a colon inside any of those
     * is left untouched. Backslash is not treated as a string-literal escape
     * (the PostgreSQL default with {@code standard_conforming_strings} on).
     */
    static ParsedSql parse(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> order = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? len : end;
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
            if (c == '\'' || c == '"') {
                i = copyQuoted(sql, i, c, out);
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
        int len = sql.length();
        int i = 0;
        boolean sawSemicolon = false;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? len : end;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c, isEscapeStringStart(sql, i, c));
                continue;
            }
            if (c == '$') {
                int consumed = skipDollarQuoted(sql, i);
                if (consumed > i) {
                    i = consumed;
                    continue;
                }
            }
            if (c == ';') {
                sawSemicolon = true;
            } else if (sawSemicolon && !Character.isWhitespace(c)) {
                return true;
            }
            i++;
        }
        return false;
    }

    /**
     * Whether the quote at {@code quotePos} opens a PostgreSQL escape string
     * ({@code E'...'} / {@code e'...'}), inside which a backslash escapes the
     * next character. Plain {@code '...'} literals do not process backslashes
     * under {@code standard_conforming_strings}.
     */
    private static boolean isEscapeStringStart(String sql, int quotePos, char quote) {
        if (quote != '\'' || quotePos == 0) {
            return false;
        }
        char prefix = sql.charAt(quotePos - 1);
        if (prefix != 'e' && prefix != 'E') {
            return false;
        }
        return quotePos - 1 == 0 || !isNamePart(sql.charAt(quotePos - 2));
    }

    private static int skipQuoted(String sql, int start, char quote, boolean backslashEscapes) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < len) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipDollarQuoted(String sql, int start) {
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
        return close < 0 ? len : close + tag.length();
    }

    private static int copyQuoted(String sql, int start, char quote, StringBuilder out) {
        int end = skipQuoted(sql, start, quote, isEscapeStringStart(sql, start, quote));
        out.append(sql, start, end);
        return end;
    }

    private static int copyDollarQuoted(String sql, int start, StringBuilder out) {
        int end = skipDollarQuoted(sql, start);
        if (end > start) {
            out.append(sql, start, end);
        }
        return end;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
