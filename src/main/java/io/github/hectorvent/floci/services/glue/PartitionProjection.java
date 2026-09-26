package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.ArrayList;
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

    private static final Set<String> TABLE_ALIAS_KEYWORDS = Set.of("where", "join", "inner", "left", "right",
            "full", "cross", "on", "group", "having", "order", "limit", "offset", "union", "intersect",
            "except", "window", "qualify", "fetch", "using");

    private PartitionProjection() {
    }

    static boolean enabled(Table table) {
        return table != null && "true".equalsIgnoreCase(parameter(table, ENABLED));
    }

    /**
     * The {@code storage.location.template} a projecting table reads from, or {@code null} when the table
     * does not project or leaves the template unset. The template can name any location, so a caller
     * that authorizes reads by the table's own location must check it separately.
     */
    public static String locationTemplate(Table table) {
        if (!enabled(table)) {
            return null;
        }
        String template = parameter(table, LOCATION_TEMPLATE);
        return template == null || template.isBlank() ? null : template;
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
        List<String> whereClauses = null;
        List<String> allAliases = tables.stream().filter(table -> table != null && table.getName() != null)
                .flatMap(table -> tableAliases(code, table.getName()).stream()).toList();
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
                if (whereClauses == null) {
                    whereClauses = whereClauses(code);
                }
                boolean unqualifiedAllowed = allAliases.size() == 1;
                List<String> clauses = whereClauses;
                if (aliases.stream().allMatch(alias -> clauses.stream()
                        .anyMatch(clause -> hasStaticEquality(clause, alias, key.getName(), unqualifiedAllowed)))) {
                    continue;
                }
                throw new AwsException("InvalidRequestException",
                        "CONSTRAINT_VIOLATION: For the injected projected partition column "
                                + key.getName() + ", the WHERE clause must contain only static equality "
                                + "conditions, and at least one such condition must be present.", 400);
            }
        }
    }

    /**
     * The text of every {@code WHERE} clause in {@code code}, a subquery's included.
     *
     * <p>A clause runs from the keyword to whatever ends it: a following clause keyword, the
     * {@code )} closing the subquery that holds it, a statement terminator, or the end of the
     * query. Parentheses opened inside the clause are part of it, so a predicate with its own
     * subquery is not cut short.
     */
    private static List<String> whereClauses(String code) {
        Set<Integer> clauseStarts = new HashSet<>();
        Matcher boundaries = CLAUSE_AFTER_WHERE.matcher(code);
        while (boundaries.find()) {
            clauseStarts.add(boundaries.start());
        }

        List<String> clauses = new ArrayList<>();
        Matcher where = WHERE_KEYWORD.matcher(code);
        while (where.find()) {
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
            clauses.add(code.substring(start, i));
        }
        return clauses;
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

    private static List<String> tableAliases(String code, String tableName) {
        String identifier = identifierPattern(tableName);
        String qualifiedName = "(?:" + IDENTIFIER + "\\s*\\.\\s*)*" + identifier;
        Pattern source = Pattern.compile("(?i)" + KEYWORD_BOUNDS.formatted("(?:FROM|JOIN)") + "\\s+" + qualifiedName
                + "(?:\\s+(?:AS\\s+)?(" + IDENTIFIER + "))?");
        List<String> aliases = new ArrayList<>();
        Matcher matcher = source.matcher(code);
        while (matcher.find()) {
            String alias = matcher.group(1);
            String normalized = alias == null || TABLE_ALIAS_KEYWORDS.contains(unquoteIdentifier(alias).toLowerCase(Locale.ROOT))
                    ? unquoteIdentifier(tableName).toLowerCase(Locale.ROOT) : unquoteIdentifier(alias).toLowerCase(Locale.ROOT);
            aliases.add(normalized);
        }
        return aliases;
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

    private static boolean hasStaticEquality(String clause, String alias, String column, boolean allowUnqualified) {
        String qualified = identifierPattern(alias) + "\\s*\\.\\s*" + identifierPattern(column);
        String left = allowUnqualified ? "(?:" + qualified + "|" + identifierPattern(column) + ")" : qualified;
        String literal = "(?:'(?:''|[^'])*'|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?|TRUE|FALSE)";
        Pattern equality = Pattern.compile("(?i)(?<![A-Za-z0-9_$])(?:" + left + "\\s*(?<![<>=!])=(?!=)\\s*"
                + literal + "|" + literal + "\\s*(?<![<>=!])=(?!=)\\s*" + left + ")(?![A-Za-z0-9_$])");
        return equality.matcher(clause).find();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
