package io.github.hectorvent.floci.core.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates SQL statements containing named placeholders ({@code :name}) into
 * JDBC-compatible positional placeholders ({@code ?}), recording placeholder order.
 *
 * <p>Handles comments (line {@code --} and block {@code /* ... *\/}), single- and
 * double-quoted string literals, MySQL backtick identifiers, PostgreSQL dollar-quoted
 * strings ({@code $tag$...$tag$}), PostgreSQL cast operators ({@code ::}), and
 * PostgreSQL escape strings ({@code E'...'}).
 *
 * <p>Shared across RDS Data API and Redshift Data API implementations.
 */
public final class SqlParameterParser {

    private SqlParameterParser() {
    }

    /**
     * Dialect-specific quoting and escape rules for SQL parameter parsing.
     *
     * @param allowBackticks whether backticks ({@code `}) delimit identifiers (e.g. MySQL / RDS Data)
     * @param backslashEscapes whether backslash escapes characters in string literals (e.g. MySQL)
     * @param escapeStrings whether PostgreSQL escape strings ({@code E'...'}/{@code e'...'})
     *                      honor backslash escapes (e.g. Redshift Data API)
     */
    public record Options(boolean allowBackticks, boolean backslashEscapes, boolean escapeStrings) {
        public static final Options REDSHIFT = new Options(false, false, true);
        public static final Options RDS_MYSQL = new Options(true, true, false);
        public static final Options RDS_POSTGRESQL = new Options(true, false, false);
    }

    /**
     * SQL rewritten with positional {@code ?} placeholders, plus the ordered
     * list of parameter names each placeholder was derived from.
     * A name repeats once per occurrence in the original SQL.
     */
    public record ParsedSql(String sql, List<String> parameterOrder) {
    }

    /**
     * Rewrites {@code :name} placeholders to positional {@code ?}, skipping over
     * string literals, quoted/backtick identifiers, line and block comments,
     * PostgreSQL {@code ::} casts, and PostgreSQL dollar-quoted strings according
     * to {@code options}.
     */
    public static ParsedSql parse(String sql, Options options) {
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

            if (c == '\'' || c == '"' || (options.allowBackticks() && c == '`')) {
                int end = skipQuoted(sql, i, c, options);
                out.append(sql, i, end);
                i = end;
                continue;
            }

            if (c == '$') {
                int consumed = skipDollarQuoted(sql, i);
                if (consumed > i) {
                    out.append(sql, i, consumed);
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
     * Whether {@code sql} holds more than one statement, applying the same
     * literal, identifier, comment, and dollar-quote skipping as {@link #parse}
     * so a {@code ;} inside any of those is not counted. Trailing {@code ;}
     * characters (with only whitespace after) are permitted.
     */
    public static boolean isMultiStatement(String sql, Options options) {
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

            if (c == '\'' || c == '"' || (options.allowBackticks() && c == '`')) {
                i = skipQuoted(sql, i, c, options);
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

    private static int skipQuoted(String sql, int start, char quote, Options options) {
        int len = sql.length();
        boolean escapable = (options.backslashEscapes() && quote != '`')
                || (options.escapeStrings() && isEscapeStringStart(sql, start, quote));
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (escapable && c == '\\' && i + 1 < len) {
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

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
