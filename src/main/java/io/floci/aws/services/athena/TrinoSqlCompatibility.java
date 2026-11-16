package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *   <li>a multi-argument {@code UNNEST} source, which DuckDB's one-argument {@code unnest} rejects, into a
 *       lateral subquery holding one {@code unnest} per array (see {@link #multiArgumentUnnest}).</li>
 * </ul>
 *
 * <p>It also reads a query string's statement, by the same lexical rules, for
 * {@code StartQueryExecution}: where it ends ({@link #holdsSeveralStatements} and {@link #recordedForm})
 * and its {@code ?} placeholders ({@link #bindParameters}).
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
    /** Words that can follow a {@code FROM} source without naming it, so never an alias given without {@code AS}. */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of("WHERE", "ON", "USING", "JOIN", "CROSS", "INNER",
            "LEFT", "RIGHT", "FULL", "NATURAL", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION",
            "INTERSECT", "EXCEPT", "WINDOW", "WITH");
    private static final Pattern MULTI_WORD_TYPE = Pattern.compile(
            "DOUBLE\\s+PRECISION|(TIMESTAMP|TIME)(\\s*\\(\\s*\\d+\\s*\\))?\\s+WITH(OUT)?\\s+TIME\\s+ZONE",
            Pattern.CASE_INSENSITIVE);

    private TrinoSqlCompatibility() {
    }

    /** {@code sql} with Trino's cast types, literal JSON paths and multi-argument UNNEST in DuckDB's syntax. */
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
            Rewrite unnest = multiArgumentUnnest(sql, i);
            if (unnest != null) {
                out.append(unnest.text());
                i = unnest.end();
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

    // ---- the statement a query string holds ----

    /**
     * Whether {@code sql} holds more than one statement: anything but whitespace on both sides of a
     * semicolon, a comment included. Athena (engine v3) rejects such a query at submission, so
     * {@code SELECT 1; -- done} fails as {@code SELECT 1; SELECT 2} does, while {@code SELECT 1;;} and
     * {@code ;SELECT 1} run. A semicolon in a literal, a quoted identifier (backticks included, as in
     * Hive DDL) or a comment separates nothing. Athena reads Hive DDL by the same rules here, so a
     * semicolon after a Hive {@code \'} escape in a literal separates statements on Athena too.
     *
     * <p>Trino ends a block comment at its first {@code *}{@code /}, but DuckDB, which runs the statement,
     * nests them. A query is taken for several statements when either reading finds a second one, so text
     * DuckDB would run as a second statement cannot pass for a comment or a literal here. Athena rejects such a
     * query too, as a syntax error.
     */
    static boolean holdsSeveralStatements(String sql) {
        return sql != null && (bounds(sql, false).several() || bounds(sql, true).several());
    }

    /**
     * {@code sql} as Athena (engine v3) records, returns and runs it: without the whitespace and semicolons
     * around it. {@code SELECT 1 ;\n} and {@code ;SELECT 1} become {@code SELECT 1}, for DDL and DML as for
     * queries. A comment at either end stays, as does a semicolon in a literal. A query holding nothing else
     * is returned as given.
     */
    static String recordedForm(String sql) {
        if (sql == null) {
            return null;
        }
        StatementBounds bounds = bounds(sql, false);
        return bounds.start() >= 0 ? sql.substring(bounds.start(), bounds.end()) : sql;
    }

    /**
     * {@code statement}, in its {@link #recordedForm}, with its {@code ?} placeholders bound to a query's
     * {@code ExecutionParameters}, as Athena (engine v3) binds them.
     *
     * <p>Athena reads the values as the SQL text of an {@code EXECUTE ... USING} list: joined with commas,
     * then read as expressions, one per placeholder in order. So a string arrives quoted ({@code 'eu'}) and
     * a number bare ({@code 10}); {@code 1 + 1} is one expression, so {@code SELECT ? * 2} gives 4; and a
     * line comment in a value runs on over the values after it, so {@code 1 -- x} and {@code 2} count as
     * one. A value that is not one expression is read as a string instead (see {@link #usingListEntry}).
     * Each expression replaces its placeholder in parentheses, with its comments blanked, so it keeps its
     * precedence inside the statement; a lone literal goes in bare, so the statement reads as if it had been
     * written there, a JSON path for {@link #translate} included.
     *
     * <p>A statement without placeholders runs as written, whatever parameters it is given, as on Athena.
     * Otherwise the counts must match. If they don't, with no parameters at all included, Athena fails the
     * query with Trino's error at where the statement starts:
     *
     * <pre>INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 2 but found 1</pre>
     *
     * A {@code ?} in a literal, a quoted identifier or a comment is not a placeholder.
     *
     * <p>TODO: named prepared statements ({@code PREPARE} / {@code EXECUTE ... USING}) bind the same way
     * but are not supported.
     *
     * @throws AwsException when the counts differ, which fails the query
     */
    static String bindParameters(String statement, List<String> parameters) {
        if (statement == null) {
            return null;
        }
        List<Integer> placeholders = new ArrayList<>();
        int i = 0;
        while (i < statement.length()) {
            int skipped = skipLiteralOrComment(statement, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            if (statement.charAt(i) == '?') {
                placeholders.add(i);
            }
            i++;
        }
        if (placeholders.isEmpty()) {
            return statement;
        }
        List<String> values = parameters == null || parameters.isEmpty()
                ? List.of()
                : splitTopLevel(String.join(", ", parameters.stream().map(TrinoSqlCompatibility::usingListEntry)
                        .toList()), true);
        if (values.size() != placeholders.size()) {
            throw new AwsException("InvalidRequestException", "INVALID_PARAMETER_USAGE: " + statementPosition(statement)
                    + ": Incorrect number of parameters: expected " + placeholders.size() + " but found " + values.size(),
                    400);
        }
        StringBuilder bound = new StringBuilder(statement.length());
        int last = 0;
        for (int k = 0; k < placeholders.size(); k++) {
            int at = placeholders.get(k);
            String expression = withoutComments(values.get(k)).strip();
            boolean literal = !expression.isEmpty() && (skipLiteralOrComment(expression, 0) == expression.length()
                    || expression.chars().allMatch(c -> Character.isDigit(c) || c == '.'));
            bound.append(statement, last, at).append(literal ? expression : "(" + expression + ")");
            last = at + 1;
        }
        return bound.append(statement, last, statement.length()).toString();
    }

    /**
     * {@code value} as Athena (engine v3) puts it in the {@code USING} list: as written when it is one
     * expression, and otherwise as a string of its tokens, whitespace and comments dropped. Athena returns
     * {@code 1); SELECT (2} as the string {@code 1);SELECT(2}, {@code 1; SELECT 2} as {@code 1;SELECT2} and
     * {@code 1, 2} as the one parameter {@code 1,2}, so a value can neither end the statement nor add a
     * parameter. A value is taken for more than one expression when, outside its literals and comments, it
     * holds a semicolon or a comma outside brackets, or its brackets don't balance.
     *
     * <p>TODO: Athena also reads a value naming a column ({@code x}) as the string {@code 'x'}; it is bound
     * as an expression here.
     */
    private static String usingListEntry(String value) {
        StringBuilder tokens = new StringBuilder(value.length());
        int depth = 0;
        boolean expression = true;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            int skipped = skipLiteralOrComment(value, i);
            if (skipped > i) {
                if (c == '\'' || c == '"') {
                    tokens.append(value, i, skipped);
                }
                i = skipped;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                expression &= --depth >= 0;
            } else if (c == ';' || c == ',' && depth == 0) {
                expression = false;
            }
            if (!Character.isWhitespace(c)) {
                tokens.append(c);
            }
            i++;
        }
        return expression && depth == 0 ? value : "'" + tokens.toString().replace("'", "''") + "'";
    }

    /**
     * Where {@code statement} starts, as Trino locates an error on it: {@code line L:C}, both counted from 1,
     * at its first token that is not a comment. {@code -- lead\nSELECT ?} is at {@code line 2:1}.
     */
    private static String statementPosition(String statement) {
        int at = Math.max(bounds(statement, false).codeStart(), 0);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < at; i++) {
            if (statement.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return "line " + line + ":" + (at - lineStart + 1);
    }

    /**
     * Where the statement of a query string lies. {@code start} is its first character that is neither
     * whitespace nor a semicolon, -1 when there is none, and {@code end} is just past its last such token: a
     * trailing comment included, the line break that ends a line comment not. {@code codeStart} is its first
     * token that is not a comment either, -1 when it is all comments. {@code several} is whether such a token
     * follows a semicolon that follows one. With {@code nestedComments}, block comments are read as DuckDB
     * reads them (see {@link #skipLiteralOrComment(String, int, boolean)}).
     */
    private record StatementBounds(int start, int end, int codeStart, boolean several) {
    }

    private static StatementBounds bounds(String sql, boolean nestedComments) {
        int start = -1;
        int end = 0;
        int codeStart = -1;
        boolean semicolonAfterStatement = false;
        boolean several = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == ';') {
                semicolonAfterStatement |= start >= 0;
                i++;
                continue;
            }
            several |= semicolonAfterStatement;
            start = start < 0 ? i : start;
            int next = skipLiteralOrComment(sql, i, nestedComments);
            boolean comment = sql.startsWith("--", i) || sql.startsWith("/*", i);
            if (next == i) {
                next = i + 1;
            }
            if (!comment && codeStart < 0) {
                codeStart = i;
            }
            // A line comment runs to its line break, which is whitespace after the statement, not part of it.
            end = next;
            while (comment && end > i && Character.isWhitespace(sql.charAt(end - 1))) {
                end--;
            }
            i = next;
        }
        return new StatementBounds(start, end, codeStart, several);
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

    /** The text replacing a construct, and the index just past the construct it replaces. */
    private record Rewrite(String text, int end) {
    }

    /**
     * The multi-argument {@code UNNEST} source starting at {@code i}, rewritten for DuckDB; null for anything else.
     *
     * <p>Trino zips the arrays of a multi-argument {@code UNNEST} into one row per position, padding the shorter ones
     * with NULL, a NULL array counting as an empty one:
     *
     * <pre>CROSS JOIN UNNEST(names, vals) AS facet (column_name, value)</pre>
     *
     * DuckDB's {@code unnest} takes one argument, but a {@code SELECT} holding several {@code unnest} calls zips them
     * the same way, NULL padding included, so the source becomes a lateral subquery with one call per array:
     *
     * <pre>CROSS JOIN LATERAL (SELECT unnest(names), unnest(vals)) AS facet (column_name, value)</pre>
     *
     * Checked against Athena (engine v3): the shorter array on either side, a NULL array beside another, both empty
     * under {@code CROSS JOIN} (no rows) and under {@code LEFT JOIN ... ON TRUE} (one row of NULLs). The column list
     * names the subquery's columns, as in Trino, rather than aliases inside it: DuckDB would resolve an array that
     * names an outer column called like an earlier alias to that alias. Each array is translated too, so a
     * {@code CAST(... AS ARRAY<JSON>)} in one still becomes DuckDB's, and comments in the arrays or the column list are
     * dropped, so a line comment cannot run on over the text around it.
     *
     * <p>Any {@code UNNEST(} with two or more arguments, an alias and a column list naming one column per array is
     * rewritten; that is only ever a {@code FROM} source. Without a column list, with one that doesn't name every
     * array, or with a clause keyword where an alias given without {@code AS} would be, the source is left as written
     * for DuckDB to reject. A single-argument {@code UNNEST} of an array needs nothing: DuckDB runs it as Athena does,
     * {@code WITH ORDINALITY} included.
     *
     * <p>TODO: left as written are a multi-argument {@code UNNEST ... WITH ORDINALITY}, one without a column list,
     * which Trino names by position, and a {@code MAP} argument, which Athena expands into key and value columns (a
     * single-argument {@code UNNEST} of a {@code MAP} fails on DuckDB too).
     */
    private static Rewrite multiArgumentUnnest(String sql, int i) {
        // A name ending in unnest, or a qualified one (schema.unnest), is some other function.
        if (!sql.regionMatches(true, i, "UNNEST", 0, 6)
                || i > 0 && (isIdentifierChar(sql.charAt(i - 1)) || sql.charAt(i - 1) == '.')) {
            return null;
        }
        int open = skipSpaceAndComments(sql, i + 6);
        int close = open < sql.length() && sql.charAt(open) == '(' ? matchingParen(sql, open) : -1;
        if (close < 0) {
            return null;
        }
        List<String> arrays = splitTopLevel(sql.substring(open + 1, close), true);
        if (arrays.size() < 2) {
            return null;
        }
        // The alias, after an optional AS; without AS, a word that ends the source is not one.
        int aliasStart = skipSpaceAndComments(sql, close + 1);
        boolean as = sql.regionMatches(true, aliasStart, "AS", 0, 2) && identifierEnd(sql, aliasStart) == aliasStart + 2;
        if (as) {
            aliasStart = skipSpaceAndComments(sql, aliasStart + 2);
        }
        int aliasEnd = identifierEnd(sql, aliasStart);
        String alias = sql.substring(aliasStart, aliasEnd);
        if (alias.isEmpty() || !as && CLAUSE_KEYWORDS.contains(alias.toUpperCase(Locale.ROOT))) {
            return null;
        }
        int columnsOpen = skipSpaceAndComments(sql, aliasEnd);
        int columnsClose = columnsOpen < sql.length() && sql.charAt(columnsOpen) == '(' ? matchingParen(sql, columnsOpen) : -1;
        if (columnsClose < 0) {
            return null;
        }
        String columnList = withoutComments(sql.substring(columnsOpen + 1, columnsClose));
        List<String> columns = splitTopLevel(columnList, true).stream().map(String::strip).toList();
        if (columns.size() != arrays.size()
                || !columns.stream().allMatch(column -> !column.isEmpty() && identifierEnd(column, 0) == column.length())) {
            return null;
        }
        StringBuilder lateral = new StringBuilder("LATERAL (SELECT ");
        for (int k = 0; k < arrays.size(); k++) {
            lateral.append(k > 0 ? ", " : "").append("unnest(")
                    .append(translate(withoutComments(arrays.get(k))).strip()).append(')');
        }
        return new Rewrite(lateral.append(") AS ").append(alias).append(" (").append(String.join(", ", columns))
                .append(')').toString(), columnsClose + 1);
    }

    /**
     * The index of the first character at or after {@code i} that is neither whitespace nor in a comment.
     * {@link AthenaIcebergStatements} finds where a statement starts with it.
     */
    static int skipSpaceAndComments(String sql, int i) {
        while (i < sql.length()) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (sql.startsWith("--", i) || sql.startsWith("/*", i)) {
                i = skipLiteralOrComment(sql, i);
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * The index just past the bare or double-quoted identifier at {@code i}, or {@code i} when none starts there.
     * A double quote that never closes starts none, so neither an {@code UNNEST} alias nor a table name can run on
     * to the end of the query. {@link AthenaIcebergStatements} reads the table a statement writes with it.
     */
    static int identifierEnd(String sql, int i) {
        if (i < sql.length() && sql.charAt(i) == '"') {
            // Not skipQuoted, which runs to the end of an unclosed quote rather than reporting it.
            int j = i + 1;
            while (j < sql.length()) {
                if (sql.charAt(j) == '"') {
                    if (j + 1 < sql.length() && sql.charAt(j + 1) == '"') {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return i;
        }
        int end = i;
        while (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
            end++;
        }
        return end;
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
        return skipLiteralOrComment(sql, i, false);
    }

    /**
     * Index just past the literal, quoted identifier or comment starting at {@code i}, or {@code i} when none
     * starts there. A backtick-quoted identifier is skipped like a double-quoted one: Trino's lexer reads it as
     * one token (and rejects it in a query), and Hive DDL names columns with it. A block comment ends at its
     * first {@code *}{@code /}, as in Trino, or, with {@code nestedComments}, at the one matching its opening,
     * as in DuckDB.
     */
    private static int skipLiteralOrComment(String sql, int i, boolean nestedComments) {
        char c = sql.charAt(i);
        if (c == '\'' || c == '"' || c == '`') {
            return skipQuoted(sql, i);
        }
        if (sql.startsWith("--", i)) {
            // A line comment ends at a carriage return as at a line feed, in Trino as in DuckDB.
            int end = i + 2;
            while (end < sql.length() && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
                end++;
            }
            return end < sql.length() ? end + 1 : sql.length();
        }
        if (sql.startsWith("/*", i) && !nestedComments) {
            int end = sql.indexOf("*/", i + 2);
            return end < 0 ? sql.length() : end + 2;
        }
        if (sql.startsWith("/*", i)) {
            int depth = 0;
            int j = i;
            while (j < sql.length()) {
                if (sql.startsWith("/*", j)) {
                    depth++;
                    j += 2;
                } else if (sql.startsWith("*/", j)) {
                    j += 2;
                    if (--depth == 0) {
                        return j;
                    }
                } else {
                    j++;
                }
            }
            return sql.length();
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
     * {@code parameters} split at its top-level commas, outside parentheses and brackets, skipping quoted
     * identifiers and, for function arguments, string literals and comments too.
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
            if (c == '<' && !sql || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' && !sql || c == ')' || c == ']') {
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
