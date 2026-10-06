package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Partition projection: the table properties describing where a table's partitions live and what
 * values they take, so the partitions need not be listed from the catalog or from storage.
 */
public final class PartitionProjection {

    /** Table property switching partition projection on. */
    private static final String ENABLED = "projection.enabled";

    /** Table property giving the partition path layout when it is not the default. */
    private static final String LOCATION_TEMPLATE = "storage.location.template";

    /** {@code ${column}} placeholders in a partition location template. */
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");

    /**
     * Not part of a longer identifier, and not a quoted one either: {@code "limit"} is a column
     * named after a keyword, and a clause keyword is never written in quotes.
     */
    private static final String KEYWORD_BOUNDS = "(?<![A-Za-z0-9_\"`])%s(?![A-Za-z0-9_\"`])";

    /** The {@code WHERE} keyword, as a keyword rather than as an identifier spelling it. */
    private static final Pattern WHERE_KEYWORD = Pattern.compile(
            KEYWORD_BOUNDS.formatted("WHERE"), Pattern.CASE_INSENSITIVE);

    /** The keywords that can end a {@code WHERE} clause by starting the next one. */
    private static final Pattern CLAUSE_AFTER_WHERE = Pattern.compile(
            KEYWORD_BOUNDS.formatted(
                    "(?:GROUP|HAVING|ORDER|WINDOW|LIMIT|OFFSET|FETCH|UNION|INTERSECT|EXCEPT)"),
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLAUSE_KEYWORD = Pattern.compile(
            KEYWORD_BOUNDS.formatted(
                    "(?:SELECT|FROM|WHERE|GROUP|HAVING|ORDER|LIMIT|OFFSET|UNION|INTERSECT|EXCEPT|JOIN|ON|USING|WINDOW|QUALIFY|FETCH)"),
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> TABLE_ALIAS_KEYWORDS = Set.of("where", "join", "inner", "left", "right",
            "full", "cross", "on", "group", "having", "order", "limit", "offset", "union", "intersect",
            "except", "window", "qualify", "fetch", "using", "natural", "lateral", "tablesample");

    private PartitionProjection() {
    }

    static boolean enabled(Table table) {
        return table != null && "true".equalsIgnoreCase(parameter(table, ENABLED));
    }

    /** Case-insensitive lookup, since table properties are not normalised on the way in. */
    static String parameter(Table table, String name) {
        Map<String, String> params = table == null ? null : table.getParameters();
        if (params == null) {
            return null;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Returns the path to read for {@code table}: its partition layout when the table projects its
     * partitions, and {@code normalizedLocation} unchanged otherwise.
     *
     * <p>A projecting table's data lives only under its partition paths, and those are what Athena
     * reads - it builds them from the projection configuration rather than listing the location.
     * Reading the location wholesale instead picks up whatever else is stored beside the data, which
     * for a table rooted at a bucket means that bucket's other prefixes. A workgroup writing query
     * results under the same root is the common way this bites: the first query succeeds, its output
     * lands inside the read path, and the next query fails trying to read it as table data.
     *
     * <p>The layout comes from {@code storage.location.template} when set, and otherwise from the
     * default {@code column=value} form over the partition keys. Values stay wildcards: narrowing
     * them to the partitions a query actually needs requires that query's predicates, so the read
     * stays wide and the query itself does the filtering.
     */
    public static String readPath(Table table, String normalizedLocation) {
        if (!enabled(table)) {
            return normalizedLocation;
        }

        String template = parameter(table, LOCATION_TEMPLATE);
        if (template != null && !template.isBlank()) {
            String wildcarded = TEMPLATE_PLACEHOLDER.matcher(template).replaceAll("*");
            return stripTrailingSlash(wildcarded);
        }

        List<Column> keys = table.getPartitionKeys();
        if (keys == null || keys.isEmpty()) {
            return normalizedLocation;
        }
        StringBuilder sb = new StringBuilder(normalizedLocation);
        for (Column key : keys) {
            if (key == null || key.getName() == null || key.getName().isBlank()) {
                return normalizedLocation;
            }
            sb.append('/').append(key.getName()).append("=*");
        }
        return sb.toString();
    }

    /**
     * Fails a query that names a projecting table but never constrains one of its {@code injected}
     * partition columns in a {@code WHERE} clause.
     *
     * <p>An injected column has no generatable range: its values come from the query, so Athena
     * requires a static equality condition on it in the {@code WHERE} clause and rejects the query
     * otherwise. Each source table alias is checked independently, and joins must qualify the
     * partition column so a filter on one table cannot stand in for another table's filter.
     *
     * <p>Scoping to the {@code WHERE} clause is what makes the check useful. A mention anywhere in
     * the query text also matches the column's own appearance in a {@code SELECT} list or a
     * {@code GROUP BY}, so {@code SELECT tenant, count(*) FROM audit_events GROUP BY tenant} would
     * pass while filtering nothing at all.
     */
    public static void assertInjectedColumnsFiltered(String query, List<Table> tables) {
        if (query == null || tables == null) {
            return;
        }
        // Literals and comments are not code: a column name inside either constrains nothing.
        String code = maskLiteralsAndComments(query);
        List<WhereScope> scopes = null;
        for (Table table : tables) {
            if (!enabled(table) || table.getName() == null || table.getPartitionKeys() == null) {
                continue;
            }
            // A table the query never reads cannot be constrained by it, and must not fail it.
            List<String> aliases = tableAliases(code, table.getName());
            if (aliases.isEmpty()) {
                continue;
            }
            for (Column key : table.getPartitionKeys()) {
                if (key == null || key.getName() == null) {
                    continue;
                }
                String type = parameter(table, "projection." + key.getName() + ".type");
                if (!"injected".equalsIgnoreCase(type)) {
                    continue;
                }
                if (scopes == null) {
                    scopes = whereScopes(code);
                }
                List<WhereScope> currentScopes = scopes;
                boolean allFiltered = !aliases.isEmpty() && aliases.stream().allMatch(alias ->
                        currentScopes.stream().anyMatch(scope ->
                                isAliasFiltered(scope, table, alias, key.getName(), tables))
                );
                if (allFiltered) {
                    continue;
                }
                throw new AwsException("InvalidRequestException",
                        "CONSTRAINT_VIOLATION: For the injected projected partition column "
                                + key.getName() + ", the WHERE clause must contain only static equality "
                                + "conditions, and at least one such condition must be present.", 400);
            }
        }
    }

    private static boolean isAliasFiltered(WhereScope scope, Table table, String alias, String column,
                                           List<Table> tables) {
        if (!tableAliases(scope.fromClause(), table.getName()).contains(alias)) {
            return false;
        }
        Set<String> sourceCols = sourceColumns(scope, tables);
        Set<String> sourceAliases = sourceAliases(scope, tables);
        if (hasStaticEquality(scope.whereClause(), alias, column, false, sourceCols, sourceAliases)) {
            return true;
        }
        long declaringSources = tables.stream()
                .filter(other -> other != null && other.getName() != null && declaresColumn(other, column))
                .mapToLong(other -> tableAliases(scope.fromClause(), other.getName()).size())
                .sum();
        if (declaringSources == 1
                && hasStaticEquality(scope.whereClause(), alias, column, true, sourceCols, sourceAliases)) {
            return true;
        }
        return false;
    }

    private static Set<String> sourceColumns(WhereScope scope, List<Table> tables) {
        Set<String> columns = new HashSet<>();
        for (Table table : tables) {
            if (table == null || table.getName() == null) {
                continue;
            }
            if (!tableAliases(scope.fromClause(), table.getName()).isEmpty()) {
                if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
                    for (Column col : table.getStorageDescriptor().getColumns()) {
                        if (col != null && col.getName() != null && !col.getName().isBlank()) {
                            columns.add(col.getName().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (table.getPartitionKeys() != null) {
                    for (Column key : table.getPartitionKeys()) {
                        if (key != null && key.getName() != null && !key.getName().isBlank()) {
                            columns.add(key.getName().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        return columns;
    }

    private static Set<String> sourceAliases(WhereScope scope, List<Table> tables) {
        Set<String> aliases = new HashSet<>();
        for (Table table : tables) {
            if (table == null || table.getName() == null) {
                continue;
            }
            List<String> found = tableAliases(scope.fromClause(), table.getName());
            if (!found.isEmpty()) {
                aliases.add(unquoteIdentifier(table.getName()).toLowerCase(Locale.ROOT));
                aliases.addAll(found);
            }
        }
        return aliases;
    }

    private record WhereScope(String fromClause, String whereClause) {
    }

    /**
     * Extracts every {@code WHERE} clause in {@code code} together with its preceding {@code FROM} clause.
     */
    private static List<WhereScope> whereScopes(String code) {
        Set<Integer> clauseStarts = new HashSet<>();
        Matcher boundaries = CLAUSE_AFTER_WHERE.matcher(code);
        while (boundaries.find()) {
            clauseStarts.add(boundaries.start());
        }

        List<WhereScope> scopes = new ArrayList<>();
        Matcher where = WHERE_KEYWORD.matcher(code);
        while (where.find()) {
            int whereStart = where.start();
            int start = where.end();
            int depth = 0;
            int i = start;
            while (i < code.length()) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0 && (c == ';' || clauseStarts.contains(i))) {
                    break;
                }
                i++;
            }
            String whereClause = code.substring(start, i);
            String fromClause = findPrecedingFromClause(code, whereStart);
            scopes.add(new WhereScope(fromClause, maskSubqueries(whereClause)));
        }
        return scopes;
    }

    /**
     * Blanks out subqueries inside parentheses, so their internal predicates do not satisfy
     * or conflict with the outer query's injected-partition filters.
     */
    private static String maskSubqueries(String clause) {
        char[] chars = clause.toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (chars[i] == '(') {
                int k = i + 1;
                while (k < chars.length && Character.isWhitespace(chars[k])) {
                    k++;
                }
                boolean isSubquery = (k + 6 <= chars.length && clause.regionMatches(true, k, "SELECT", 0, 6)
                        && isWordBoundary(clause, k, 6))
                        || (k + 4 <= chars.length && clause.regionMatches(true, k, "WITH", 0, 4)
                        && isWordBoundary(clause, k, 4));
                if (isSubquery) {
                    int depth = 1;
                    int subStart = i + 1;
                    int p = subStart;
                    while (p < chars.length && depth > 0) {
                        if (chars[p] == '(') {
                            depth++;
                        } else if (chars[p] == ')') {
                            depth--;
                        }
                        p++;
                    }
                    int subEnd = depth == 0 ? p - 1 : chars.length;
                    for (int m = subStart; m < subEnd; m++) {
                        if (chars[m] != '\n') {
                            chars[m] = ' ';
                        }
                    }
                    i = subEnd;
                    continue;
                }
            }
            i++;
        }
        return new String(chars);
    }

    private static String findPrecedingFromClause(String code, int whereStart) {
        int depth = 0;
        for (int i = whereStart - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0) {
                if (c == ';') {
                    break;
                }
                if (code.regionMatches(true, i, "FROM", 0, 4) && isWordBoundary(code, i, 4)) {
                    return code.substring(i, whereStart);
                }
                if ((code.regionMatches(true, i, "SELECT", 0, 6) && isWordBoundary(code, i, 6))
                        || (code.regionMatches(true, i, "UNION", 0, 5) && isWordBoundary(code, i, 5))
                        || (code.regionMatches(true, i, "INTERSECT", 0, 9) && isWordBoundary(code, i, 9))
                        || (code.regionMatches(true, i, "EXCEPT", 0, 6) && isWordBoundary(code, i, 6))) {
                    break;
                }
            }
        }
        return "";
    }

    /**
     * Blanks out single-quoted literals and comments, leaving everything else - and every index -
     * where it was. Double-quoted and backquoted identifiers stay: those are references to a column,
     * not text that happens to spell its name.
     */
    private static String maskLiteralsAndComments(String sql) {
        char[] chars = sql.toCharArray();
        int i = 0;
        while (i < chars.length) {
            int end;
            if (chars[i] == '\'') {
                end = i + 1;
                while (end < chars.length) {
                    if (chars[end] != '\'') {
                        end++;
                    } else if (end + 1 < chars.length && chars[end + 1] == '\'') {
                        end += 2;   // an escaped quote, still inside the literal
                    } else {
                        end++;
                        break;
                    }
                }
            } else if (chars[i] == '-' && i + 1 < chars.length && chars[i + 1] == '-') {
                end = i;
                while (end < chars.length && chars[end] != '\n') {
                    end++;
                }
            } else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                end = i + 2;
                while (end + 1 < chars.length && !(chars[end] == '*' && chars[end + 1] == '/')) {
                    end++;
                }
                end = Math.min(chars.length, end + 2);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                boolean literalBoundary = chars[i] == '\'' && (k == i || k == end - 1 && chars[k] == '\'');
                if (chars[k] != '\n' && !literalBoundary) {
                    chars[k] = ' ';
                }
            }
            i = end;
        }
        return new String(chars);
    }

    private static boolean declaresColumn(Table table, String column) {
        List<Column> declared = new ArrayList<>();
        if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
            declared.addAll(table.getStorageDescriptor().getColumns());
        }
        if (table.getPartitionKeys() != null) {
            declared.addAll(table.getPartitionKeys());
        }
        return declared.stream().anyMatch(c -> c != null && column.equalsIgnoreCase(c.getName()));
    }

    /**
     * The alias, or the table's own name, under which each {@code FROM}, {@code JOIN} or comma-separated
     * source naming {@code tableName} appears. The name must end at an identifier boundary, so
     * {@code events_daily} is not a reference to {@code events}.
     */
    private static List<String> tableAliases(String code, String tableName) {
        String identifier = identifierPattern(tableName);
        String qualifiedName = "(?:" + IDENTIFIER + "\\s*\\.\\s*)*" + identifier + "(?![A-Za-z0-9_$])";
        String aliasPart = "(?:\\s+(?:(AS)\\s+)?(" + IDENTIFIER + "))?";
        Pattern keywordSource = Pattern.compile("(?i)" + KEYWORD_BOUNDS.formatted("(?:FROM|JOIN)") + "\\s+"
                + qualifiedName + aliasPart);
        Pattern commaSource = Pattern.compile("(?i),\\s*" + qualifiedName + aliasPart);
        List<String> aliases = new ArrayList<>();
        Matcher matcher = keywordSource.matcher(code);
        while (matcher.find()) {
            boolean hasAs = matcher.group(1) != null;
            aliases.add(sourceName(matcher.group(2), hasAs, tableName));
        }
        matcher = commaSource.matcher(code);
        while (matcher.find()) {
            if (inFromList(code, matcher.start())) {
                boolean hasAs = matcher.group(1) != null;
                aliases.add(sourceName(matcher.group(2), hasAs, tableName));
            }
        }
        return aliases;
    }

    private static String sourceName(String alias, boolean hasAs, String tableName) {
        if (alias == null) {
            return unquoteIdentifier(tableName).toLowerCase(Locale.ROOT);
        }
        boolean quoted = isQuoted(alias);
        if (quoted || hasAs) {
            return unquoteIdentifier(alias).toLowerCase(Locale.ROOT);
        }
        if (TABLE_ALIAS_KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
            return unquoteIdentifier(tableName).toLowerCase(Locale.ROOT);
        }
        return unquoteIdentifier(alias).toLowerCase(Locale.ROOT);
    }

    private static boolean isQuoted(String identifier) {
        return identifier != null && identifier.length() >= 2
                && ((identifier.startsWith("\"") && identifier.endsWith("\""))
                || (identifier.startsWith("`") && identifier.endsWith("`")));
    }

    /** True when the nearest clause keyword before {@code position} is {@code FROM} or {@code JOIN}. */
    private static boolean inFromList(String code, int position) {
        int depth = 0;
        Map<Integer, String> clauseAtDepth = new HashMap<>();
        Matcher kwMatcher = CLAUSE_KEYWORD.matcher(code);
        int cursor = 0;
        while (kwMatcher.find() && kwMatcher.start() < position) {
            for (int i = cursor; i < kwMatcher.start(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    clauseAtDepth.remove(depth);
                    depth--;
                } else if (c == ';' && depth == 0) {
                    clauseAtDepth.clear();
                }
            }
            cursor = kwMatcher.start();
            String kw = kwMatcher.group().toUpperCase(Locale.ROOT);
            if ("FROM".equals(kw) || "JOIN".equals(kw) || "ON".equals(kw) || "USING".equals(kw)) {
                clauseAtDepth.put(depth, "FROM");
            } else {
                clauseAtDepth.put(depth, kw);
            }
            cursor = kwMatcher.end();
        }
        for (int i = cursor; i < position; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                clauseAtDepth.remove(depth);
                depth--;
            } else if (c == ';' && depth == 0) {
                clauseAtDepth.clear();
            }
        }
        return "FROM".equals(clauseAtDepth.get(depth));
    }

    private static final String IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:\"\"|[^\"])+\"|`[^`]+`)";

    private static String identifierPattern(String identifier) {
        String unquoted = unquoteIdentifier(identifier);
        String doubleQuoted = "\"" + unquoted.replace("\"", "\"\"") + "\"";
        String backquoted = "`" + unquoted + "`";
        return "(?:" + Pattern.quote(unquoted) + "|" + Pattern.quote(doubleQuoted) + "|"
                + Pattern.quote(backquoted) + ")";
    }

    private static String unquoteIdentifier(String identifier) {
        if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        if (identifier.length() >= 2 && identifier.startsWith("`") && identifier.endsWith("`")) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    private static boolean hasStaticEquality(String clause, String alias, String column, boolean allowUnqualified,
                                           Set<String> sourceColumns, Set<String> sourceAliases) {
        String qualified = identifierPattern(alias) + "\\s*\\.\\s*" + identifierPattern(column);
        String left = allowUnqualified ? "(?:" + qualified + "|" + identifierPattern(column) + ")" : qualified;
        Pattern colPattern = Pattern.compile("(?<![A-Za-z0-9_$])" + left + "(?![A-Za-z0-9_$])",
                Pattern.CASE_INSENSITIVE);
        Matcher m = colPattern.matcher(clause);
        while (m.find()) {
            int colStart = m.start();
            int colEnd = m.end();

            // 1. IN list: col [NOT] IN (...) or NOT col IN (...)
            int afterCol = colEnd;
            while (afterCol < clause.length() && Character.isWhitespace(clause.charAt(afterCol))) {
                afterCol++;
            }
            boolean isNotIn = isPrecededByNot(clause, colStart);
            if (afterCol + 3 <= clause.length() && clause.regionMatches(true, afterCol, "NOT", 0, 3)
                    && isWordBoundary(clause, afterCol, 3)) {
                isNotIn = true;
                afterCol += 3;
                while (afterCol < clause.length() && Character.isWhitespace(clause.charAt(afterCol))) {
                    afterCol++;
                }
            }
            if (afterCol + 2 <= clause.length() && clause.regionMatches(true, afterCol, "IN", 0, 2)
                    && isWordBoundary(clause, afterCol, 2)) {
                if (!isNotIn) {
                    int afterIn = afterCol + 2;
                    while (afterIn < clause.length() && Character.isWhitespace(clause.charAt(afterIn))) {
                        afterIn++;
                    }
                    if (afterIn < clause.length() && clause.charAt(afterIn) == '(') {
                        int parenStart = afterIn;
                        int parenEnd = findMatchingParen(clause, parenStart);
                        if (parenEnd != -1) {
                            String listContent = clause.substring(parenStart + 1, parenEnd);
                            List<String> items = splitList(listContent);
                            if (!items.isEmpty() && items.size() <= 1000
                                    && items.stream().allMatch(item ->
                                            isValidValue(item, sourceColumns, sourceAliases))) {
                                return true;
                            }
                        }
                    }
                }
                continue;
            }

            // 2. Equality on the left: col = value
            int p = colEnd;
            while (p < clause.length()) {
                char c = clause.charAt(p);
                if (Character.isWhitespace(c) || c == ')') {
                    p++;
                } else {
                    break;
                }
            }
            if (p < clause.length() && clause.charAt(p) == '=') {
                if (isSingleEquals(clause, p, colEnd)) {
                    int valStart = p + 1;
                    int depth = 0;
                    int k = valStart;
                    while (k < clause.length()) {
                        char c = clause.charAt(k);
                        if (c == '(') {
                            depth++;
                        } else if (c == ')') {
                            if (depth == 0) {
                                break;
                            }
                            depth--;
                        } else if (depth == 0) {
                            if (c == ';') {
                                break;
                            }
                            if (isKeywordAt(clause, k, "AND") || isKeywordAt(clause, k, "OR")) {
                                break;
                            }
                        }
                        k++;
                    }
                    String valExpr = clause.substring(valStart, k).trim();
                    if (isValidValue(valExpr, sourceColumns, sourceAliases)) {
                        return true;
                    }
                }
            }

            // 3. Equality on the right: value = col
            int q = colStart - 1;
            while (q >= 0) {
                char c = clause.charAt(q);
                if (Character.isWhitespace(c) || c == '(') {
                    q--;
                } else {
                    break;
                }
            }
            if (q >= 0 && clause.charAt(q) == '=') {
                if (isSingleEquals(clause, q, 0)) {
                    int valEnd = q;
                    int depth = 0;
                    int j = q - 1;
                    while (j >= 0) {
                        char c = clause.charAt(j);
                        if (c == ')') {
                            depth++;
                        } else if (c == '(') {
                            if (depth == 0) {
                                break;
                            }
                            depth--;
                        } else if (depth == 0) {
                            if (c == ';') {
                                break;
                            }
                            if (isKeywordBefore(clause, j, "AND") || isKeywordBefore(clause, j, "OR")) {
                                break;
                            }
                        }
                        j--;
                    }
                    int valStart = j + 1;
                    String valExpr = clause.substring(valStart, valEnd).trim();
                    if (isValidValue(valExpr, sourceColumns, sourceAliases)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isValidValue(String expr, Set<String> sourceColumns, Set<String> sourceAliases) {
        if (expr == null) {
            return false;
        }
        String stripped = stripParens(expr).trim();
        if (stripped.isEmpty()) {
            return false;
        }
        return !namesSourceColumn(expr, sourceColumns, sourceAliases);
    }

    private static boolean namesSourceColumn(String expr, Set<String> sourceColumns, Set<String> sourceAliases) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        for (String col : sourceColumns) {
            Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$])" + identifierPattern(col)
                    + "(?![A-Za-z0-9_$])(?!\\s*\\()", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(expr);
            while (matcher.find()) {
                int matchStart = matcher.start();
                int b = matchStart - 1;
                while (b >= 0 && Character.isWhitespace(expr.charAt(b))) {
                    b--;
                }
                if (b >= 1 && expr.regionMatches(true, b - 1, "AS", 0, 2)
                        && isWordBoundary(expr, b - 1, 2)) {
                    continue;
                }
                return true;
            }
        }
        Pattern dotChainPattern = Pattern.compile("(?<![A-Za-z0-9_$])" + IDENTIFIER
                + "(?:\\s*\\.\\s*" + IDENTIFIER + ")+", Pattern.CASE_INSENSITIVE);
        Matcher dotMatcher = dotChainPattern.matcher(expr);
        while (dotMatcher.find()) {
            int end = dotMatcher.end();
            while (end < expr.length() && Character.isWhitespace(expr.charAt(end))) {
                end++;
            }
            if (end < expr.length() && expr.charAt(end) == '(') {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isSingleEquals(String text, int equalsIndex, int minBeforeIndex) {
        int before = equalsIndex - 1;
        while (before >= minBeforeIndex && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        if (before >= 0) {
            char b = text.charAt(before);
            if (b == '!' || b == '<' || b == '>' || b == '=') {
                return false;
            }
        }
        int after = equalsIndex + 1;
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) {
            after++;
        }
        if (after < text.length()) {
            char a = text.charAt(after);
            if (a == '=' || a == '<' || a == '>') {
                return false;
            }
        }
        return true;
    }

    private static int findMatchingParen(String text, int openParenIndex) {
        int depth = 1;
        int i = openParenIndex + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return depth == 0 ? i - 1 : -1;
    }

    private static boolean isPrecededByNot(String text, int colStart) {
        int j = colStart - 1;
        while (j >= 0 && (Character.isWhitespace(text.charAt(j)) || text.charAt(j) == '(')) {
            j--;
        }
        if (j < 2) {
            return false;
        }
        return text.regionMatches(true, j - 2, "NOT", 0, 3) && isWordBoundary(text, j - 2, 3);
    }

    private static boolean isKeywordAt(String text, int index, String keyword) {
        int len = keyword.length();
        if (index + len > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, index, keyword, 0, len)) {
            return false;
        }
        return isWordBoundary(text, index, len);
    }

    private static boolean isKeywordBefore(String text, int index, String keyword) {
        int len = keyword.length();
        int end = index;
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
            end--;
        }
        if (end + 1 < len) {
            return false;
        }
        int start = end - len + 1;
        return text.regionMatches(true, start, keyword, 0, len) && isWordBoundary(text, start, len);
    }

    private static String stripParens(String expr) {
        if (expr == null) {
            return "";
        }
        String trimmed = expr.trim();
        while (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            int depth = 0;
            boolean matching = false;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        matching = (i == trimmed.length() - 1);
                        break;
                    }
                }
            }
            if (!matching) {
                break;
            }
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static List<String> splitList(String content) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ',' && depth == 0) {
                String item = content.substring(start, i).trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
                start = i + 1;
            }
        }
        String last = content.substring(start).trim();
        if (!last.isEmpty()) {
            items.add(last);
        }
        return items;
    }

    private static boolean isWordBoundary(String text, int start, int length) {
        if (start > 0) {
            char before = text.charAt(start - 1);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '$' || before == '"' || before == '`') {
                return false;
            }
        }
        int end = start + length;
        if (end < text.length()) {
            char after = text.charAt(end);
            if (Character.isLetterOrDigit(after) || after == '_' || after == '$' || after == '"' || after == '`') {
                return false;
            }
        }
        return true;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
