package io.floci.aws.services.athena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bridges Trino SQL, the dialect Athena engine version 3 runs, and the DuckDB sidecar Floci runs
 * it on.
 *
 * <p>{@link #FUNCTION_MACROS} defines, as DuckDB macros, the Trino JSON functions DuckDB lacks or
 * answers differently: {@code json_extract_scalar} (NULL for an object or array, where DuckDB's
 * {@code json_extract_string} returns its text), {@code json_parse}, {@code json_format},
 * {@code json_size} and {@code is_json_scalar}. {@code json_extract} and {@code json_array_length}
 * are DuckDB's own and already answer as Trino does. DuckDB keeps macros and views apart, so a Glue
 * table named like one of them still gets its view.
 *
 * <p>{@link #translate} rewrites, outside string literals, quoted identifiers and comments:
 * <ul>
 *   <li>the target type of every {@code CAST} and {@code TRY_CAST}: {@code ARRAY<T>} and
 *       {@code ARRAY(T)} become {@code T[]}, {@code MAP<K, V>} becomes {@code MAP(K, V)} and
 *       {@code ROW(name T, ...)} becomes {@code STRUCT(name T, ...)}, an unnamed field taking the
 *       name {@code field<n>}, so {@code CAST(json_parse(col) AS ARRAY<JSON>)} parses;</li>
 *   <li>a literal JSON path given to {@code json_extract_scalar}, {@code json_extract} or
 *       {@code json_size}, from Trino's JSONPath into a JSON Pointer, which DuckDB also takes and
 *       which can name any member, a bracketed {@code ["a\"b"]} included. A path with a wildcard
 *       keeps its JSONPath form, and the macros still turn a computed path's bracketed members
 *       ({@code $["a b"]}) into DuckDB's {@code $."a b"}.</li>
 * </ul>
 */
final class TrinoSqlCompatibility {

    private static final String JSON_PATH = "regexp_replace(p, '\\[\\s*\"([^\"]*)\"\\s*\\]', '.\"\\1\"', 'g')";

    static final String FUNCTION_MACROS = String.join("\n",
            "CREATE OR REPLACE MACRO json_extract_scalar(j, p) AS CASE WHEN json_type(json_extract(j, "
                    + JSON_PATH + ")) IN ('OBJECT', 'ARRAY') THEN NULL ELSE json_extract_string(j, " + JSON_PATH
                    + ") END;",
            "CREATE OR REPLACE MACRO json_parse(s) AS json(s);",
            "CREATE OR REPLACE MACRO json_format(j) AS CAST(json(j) AS VARCHAR);",
            "CREATE OR REPLACE MACRO json_size(j, p) AS CAST(CASE WHEN json_extract(j, " + JSON_PATH + ") IS NULL"
                    + " THEN NULL WHEN json_type(json_extract(j, " + JSON_PATH + ")) = 'ARRAY'"
                    + " THEN json_array_length(j, " + JSON_PATH + ")"
                    + " WHEN json_type(json_extract(j, " + JSON_PATH + ")) = 'OBJECT'"
                    + " THEN len(json_keys(j, " + JSON_PATH + ")) ELSE 0 END AS BIGINT);",
            "CREATE OR REPLACE MACRO is_json_scalar(j) AS json_type(json(j)) NOT IN ('OBJECT', 'ARRAY');",
            "");

    private static final List<String> CAST_FUNCTIONS = List.of("TRY_CAST", "CAST");
    private static final List<String> PATH_FUNCTIONS = List.of("JSON_EXTRACT_SCALAR", "JSON_EXTRACT", "JSON_SIZE");
    private static final Pattern MULTI_WORD_TYPE = Pattern.compile(
            "DOUBLE\\s+PRECISION|(TIMESTAMP|TIME)(\\s*\\(\\s*\\d+\\s*\\))?\\s+WITH(OUT)?\\s+TIME\\s+ZONE",
            Pattern.CASE_INSENSITIVE);

    private TrinoSqlCompatibility() {
    }

    /** {@code sql} with Trino's cast types and literal JSON paths in DuckDB's syntax. */
    static String translate(String sql) {
        if (sql == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            int skipped = skipLiteralOrComment(sql, i);
            if (skipped > i) {
                out.append(sql, i, skipped);
                i = skipped;
                continue;
            }
            String function = functionAt(sql, i);
            int open = function == null ? -1 : sql.indexOf('(', i + function.length());
            int close = open < 0 ? -1 : matchingParen(sql, open);
            if (close < 0) {
                out.append(sql.charAt(i));
                i++;
                continue;
            }
            String inner = sql.substring(open + 1, close);
            out.append(sql, i, open + 1)
                    .append(CAST_FUNCTIONS.contains(function) ? translateCast(inner) : translatePathCall(inner))
                    .append(')');
            i = close + 1;
        }
        return out.toString();
    }

    private static String translateCast(String inner) {
        int as = lastTopLevelAs(inner);
        if (as < 0) {
            return translate(inner);
        }
        return translate(inner.substring(0, as)) + inner.substring(as, as + 2) + " "
                + translateType(withoutComments(inner.substring(as + 2)).trim());
    }

    private static String translatePathCall(String inner) {
        List<String> arguments = splitTopLevel(inner, true);
        if (arguments.size() != 2) {
            return translate(inner);
        }
        String path = arguments.get(1);
        String pointer = jsonPointerLiteral(path.trim());
        return translate(arguments.get(0)) + "," + (pointer == null ? translate(path) : " " + pointer);
    }

    /**
     * {@code literal}, a SQL string holding a Trino JSONPath made of member names ({@code .a},
     * {@code ["a b"]}) and array indexes, as a SQL string holding the JSON Pointer to the same value;
     * null for anything else, a wildcard, a filter or a computed path included.
     */
    static String jsonPointerLiteral(String literal) {
        if (literal.length() < 2 || literal.charAt(0) != '\'' || literal.charAt(literal.length() - 1) != '\''
                || skipLiteralOrComment(literal, 0) != literal.length()) {
            return null;
        }
        String path = literal.substring(1, literal.length() - 1).replace("''", "'").trim();
        if (!path.startsWith("$")) {
            return null;
        }
        StringBuilder pointer = new StringBuilder();
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && (Character.isLetterOrDigit(path.charAt(end)) || path.charAt(end) == '_')) {
                    end++;
                }
                if (end == i + 1) {
                    return null;
                }
                pointer.append('/').append(pointerToken(path.substring(i + 1, end)));
                i = end;
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                String member = end < 0 ? null : bracketMember(path, i + 1);
                if (member != null) {
                    pointer.append('/').append(pointerToken(member));
                    i = bracketMemberEnd(path, i + 1);
                } else if (end > i + 1 && path.substring(i + 1, end).trim().chars().allMatch(Character::isDigit)) {
                    pointer.append('/').append(path.substring(i + 1, end).trim());
                    i = end + 1;
                } else {
                    return null;
                }
            } else {
                return null;
            }
            if (i < 0) {
                return null;
            }
        }
        return "'" + pointer.toString().replace("'", "''") + "'";
    }

    /** The name of a {@code ["..."]} member starting at {@code start}, its escapes undone, or null. */
    private static String bracketMember(String path, int start) {
        int i = start;
        while (i < path.length() && Character.isWhitespace(path.charAt(i))) {
            i++;
        }
        if (i >= path.length() || path.charAt(i) != '"') {
            return null;
        }
        StringBuilder name = new StringBuilder();
        for (i++; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length()) {
                name.append(path.charAt(++i));
            } else if (c == '"') {
                return name.toString();
            } else {
                name.append(c);
            }
        }
        return null;
    }

    /** Index just past the {@code ]} closing a {@code ["..."]} member starting at {@code start}, or -1. */
    private static int bracketMemberEnd(String path, int start) {
        int i = path.indexOf('"', start) + 1;
        while (i < path.length() && path.charAt(i) != '"') {
            i += path.charAt(i) == '\\' ? 2 : 1;
        }
        int close = i + 1;
        while (close < path.length() && Character.isWhitespace(path.charAt(close))) {
            close++;
        }
        return close < path.length() && path.charAt(close) == ']' ? close + 1 : -1;
    }

    private static String pointerToken(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /** A Trino type name in DuckDB's syntax. */
    static String translateType(String type) {
        int open = firstGenericOpen(type);
        if (open < 0) {
            return type;
        }
        String head = type.substring(0, open).trim().toUpperCase(Locale.ROOT);
        int close = matchingGenericClose(type, open);
        if (close != type.length() - 1) {
            return type;
        }
        List<String> parameters = splitTopLevel(type.substring(open + 1, close), false);
        return switch (head) {
            case "ARRAY" -> parameters.size() == 1 ? translateType(parameters.getFirst().trim()) + "[]" : type;
            case "MAP" -> parameters.size() == 2
                    ? "MAP(" + translateType(parameters.get(0).trim()) + ", " + translateType(parameters.get(1).trim()) + ")"
                    : type;
            case "ROW" -> rowType(parameters);
            default -> type;
        };
    }

    private static String rowType(List<String> fields) {
        List<String> translated = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i).trim();
            int nameEnd = nameEnd(field);
            boolean unnamed = nameEnd < 0 || MULTI_WORD_TYPE.matcher(field).matches()
                    || field.substring(nameEnd).trim().startsWith("<") || field.substring(nameEnd).trim().startsWith("(");
            translated.add(unnamed
                    ? "field" + i + " " + translateType(field)
                    : field.substring(0, nameEnd) + " " + translateType(field.substring(nameEnd).trim()));
        }
        return "STRUCT(" + String.join(", ", translated) + ")";
    }

    /** End of a ROW field's name: after a quoted name, or at the first whitespace; -1 for a lone type. */
    private static int nameEnd(String field) {
        if (field.startsWith("\"")) {
            int end = skipQuoted(field, 0);
            return end < field.length() ? end : -1;
        }
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '<' || c == '(') {
                return -1;
            }
            if (Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    /** The CAST or path-taking function whose name starts at {@code i} and is followed by {@code (}, or null. */
    private static String functionAt(String sql, int i) {
        if (i > 0 && isIdentifierChar(sql.charAt(i - 1))) {
            return null;
        }
        for (List<String> names : List.of(CAST_FUNCTIONS, PATH_FUNCTIONS)) {
            for (String name : names) {
                if (sql.regionMatches(true, i, name, 0, name.length())) {
                    int j = i + name.length();
                    while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) {
                        j++;
                    }
                    if (j < sql.length() && sql.charAt(j) == '(') {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    private static int matchingParen(String sql, int open) {
        int depth = 0;
        int i = open;
        while (i < sql.length()) {
            int skipped = skipLiteralOrComment(sql, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Index of the last {@code AS} keyword outside parentheses, literals and comments, or -1. */
    private static int lastTopLevelAs(String inner) {
        int depth = 0;
        int found = -1;
        int i = 0;
        while (i < inner.length()) {
            int skipped = skipLiteralOrComment(inner, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && inner.regionMatches(true, i, "AS", 0, 2)
                    && (i == 0 || !isIdentifierChar(inner.charAt(i - 1)))
                    && (i + 2 == inner.length() || !isIdentifierChar(inner.charAt(i + 2)))) {
                found = i;
            }
            i++;
        }
        return found;
    }

    /** {@code text} with each comment outside literals and quoted identifiers turned into a space. */
    private static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int skipped = skipLiteralOrComment(text, i);
            if (skipped > i) {
                boolean comment = text.startsWith("--", i) || text.startsWith("/*", i);
                out.append(comment ? " " : text.substring(i, skipped));
                i = skipped;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static int skipLiteralOrComment(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '\'' || c == '"') {
            return skipQuoted(sql, i);
        }
        if (sql.startsWith("--", i)) {
            int end = sql.indexOf('\n', i);
            return end < 0 ? sql.length() : end + 1;
        }
        if (sql.startsWith("/*", i)) {
            int end = sql.indexOf("*/", i + 2);
            return end < 0 ? sql.length() : end + 2;
        }
        return i;
    }

    /** Index just past the literal or quoted identifier starting at {@code i}, doubled quotes included. */
    private static int skipQuoted(String sql, int i) {
        char quote = sql.charAt(i);
        int j = i + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }

    private static int firstGenericOpen(String type) {
        int i = 0;
        while (i < type.length()) {
            char c = type.charAt(i);
            if (c == '"') {
                i = skipQuoted(type, i);
                continue;
            }
            if (c == '<' || c == '(') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int matchingGenericClose(String type, int open) {
        int depth = 0;
        int i = open;
        while (i < type.length()) {
            char c = type.charAt(i);
            if (c == '"') {
                i = skipQuoted(type, i);
                continue;
            }
            if (c == '<' || c == '(') {
                depth++;
            } else if ((c == '>' || c == ')') && --depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * {@code parameters} split at its top-level commas, skipping quoted identifiers and, for
     * function arguments, string literals and comments too.
     */
    private static List<String> splitTopLevel(String parameters, boolean sql) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < parameters.length()) {
            char c = parameters.charAt(i);
            int skipped = sql ? skipLiteralOrComment(parameters, i) : c == '"' ? skipQuoted(parameters, i) : i;
            if (skipped > i) {
                i = skipped;
                continue;
            }
            if (c == '<' && !sql || c == '(') {
                depth++;
            } else if (c == '>' && !sql || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(parameters.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(parameters.substring(start));
        return parts;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
