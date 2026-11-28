package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code DataFilter} of a zero-ETL integration from a PostgreSQL source: a comma-separated list
 * of {@code include: database.schema.table} and {@code exclude: database.schema.table} entries in
 * Maxwell filter syntax. A schema or table part may be {@code *} or a {@code /regex/}; the database
 * part has to be a literal or {@code *}, since RDS takes no regular expression there.
 *
 * <p>RDS requires a PostgreSQL integration to name at least one database it includes, at most 99
 * patterns, and no pattern longer than 256 characters.
 */
public record IntegrationDataFilter(List<Rule> rules) {

    static final int MAX_LENGTH = 25_600;
    static final int MAX_PATTERNS = 99;
    static final int MAX_PATTERN_LENGTH = 256;
    // The DataFilter shape's modelled pattern.
    private static final Pattern MODELLED = Pattern.compile("[a-zA-Z0-9_ \"\\\\\\-$,*.:?+/]*");
    private static final int POSTGRES_PARTS = 3;

    /** One {@code include:} or {@code exclude:} entry, split into its database, schema and table parts. */
    public record Rule(boolean include, List<String> parts) {

        public String database() {
            return parts.getFirst();
        }

        static boolean isWildcard(String part) {
            return "*".equals(part);
        }

        static boolean isRegex(String part) {
            return part.length() >= 2 && part.startsWith("/") && part.endsWith("/");
        }
    }

    public IntegrationDataFilter {
        rules = List.copyOf(rules);
    }

    /** Parses and validates a PostgreSQL integration's filter, refusing a missing one. */
    public static IntegrationDataFilter parsePostgres(String dataFilter) {
        if (dataFilter == null || dataFilter.isBlank()) {
            throw invalid("A data filter that includes at least one database (database-name.*.*) is required "
                    + "for a PostgreSQL source.");
        }
        if (dataFilter.length() > MAX_LENGTH || !MODELLED.matcher(dataFilter).matches()) {
            throw invalid("The data filter " + dataFilter + " is not valid.");
        }
        List<Rule> rules = new ArrayList<>();
        for (String entry : splitOutsideRegex(dataFilter, ',')) {
            rules.add(parseRule(entry.trim(), dataFilter));
        }
        if (rules.size() > MAX_PATTERNS) {
            throw invalid("A data filter can have at most " + MAX_PATTERNS + " patterns.");
        }
        IntegrationDataFilter filter = new IntegrationDataFilter(rules);
        if (filter.includedDatabases().isEmpty()) {
            throw invalid("The data filter must include at least one database (database-name.*.*) "
                    + "for a PostgreSQL source.");
        }
        return filter;
    }

    /** The databases an {@code include:} entry names literally, in the order they first appear. */
    public Set<String> includedDatabases() {
        Set<String> databases = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (rule.include() && !Rule.isWildcard(rule.database())) {
                databases.add(rule.database());
            }
        }
        return databases;
    }

    /** Whether some {@code include:} entry can match a table of {@code database}. */
    public boolean includesDatabase(String database) {
        return rules.stream().anyMatch(rule -> rule.include() && matches(rule.database(), database));
    }

    /**
     * Whether the filter replicates the table. As on RDS, the entries are applied in order and the
     * last one that matches decides; a table no entry matches is not replicated, since a filter
     * switches the default from {@code include: *.*.*} to {@code exclude: *.*.*}.
     */
    public boolean includes(String database, String schema, String table) {
        boolean included = false;
        for (Rule rule : rules) {
            List<String> parts = rule.parts();
            if (matches(parts.get(0), database) && matches(parts.get(1), schema) && matches(parts.get(2), table)) {
                included = rule.include();
            }
        }
        return included;
    }

    /** A {@code *}, a {@code /regex/} matching the whole name, or a name, which may be double-quoted. */
    private static boolean matches(String part, String name) {
        if (Rule.isWildcard(part)) {
            return true;
        }
        if (Rule.isRegex(part)) {
            return Pattern.compile(part.substring(1, part.length() - 1)).matcher(name).matches();
        }
        if (part.length() >= 2 && part.startsWith("\"") && part.endsWith("\"")) {
            return part.substring(1, part.length() - 1).replace("\"\"", "\"").equals(name);
        }
        return part.equals(name);
    }

    private static Rule parseRule(String entry, String dataFilter) {
        int colon = entry.indexOf(':');
        if (colon < 0) {
            throw invalid("The data filter " + dataFilter + " is not valid: each pattern must start with "
                    + "include: or exclude:.");
        }
        String type = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String pattern = entry.substring(colon + 1).trim();
        boolean include = switch (type) {
            case "include" -> true;
            case "exclude" -> false;
            default -> throw invalid("The data filter " + dataFilter + " is not valid: each pattern must start "
                    + "with include: or exclude:.");
        };
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw invalid("A data filter pattern can be at most " + MAX_PATTERN_LENGTH + " characters.");
        }
        List<String> parts = splitOutsideRegex(pattern, '.');
        if (parts.size() != POSTGRES_PARTS || parts.stream().anyMatch(String::isBlank)) {
            throw invalid("The data filter pattern " + pattern + " is not valid: a PostgreSQL pattern has the "
                    + "form database.schema.table.");
        }
        if (Rule.isRegex(parts.getFirst())) {
            throw invalid("The data filter pattern " + pattern + " is not valid: the database name of a "
                    + "PostgreSQL pattern can't be a regular expression.");
        }
        for (String part : parts) {
            if (Rule.isRegex(part)) {
                String regex = part.substring(1, part.length() - 1);
                if (regex.isEmpty() || hasUnescapedSlash(regex)) {
                    throw invalid("The data filter pattern " + pattern + " is not valid: " + part
                            + " is not a valid regular expression.");
                }
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    throw invalid("The data filter pattern " + pattern + " is not valid: " + part
                            + " is not a valid regular expression.");
                }
            } else if (part.chars().anyMatch(Character::isWhitespace)) {
                throw invalid("The data filter pattern " + pattern + " is not valid: a PostgreSQL pattern has the "
                        + "form database.schema.table.");
            } else if (part.indexOf('/') >= 0) {
                throw invalid("The data filter pattern " + pattern + " is not valid: " + part
                        + " has an unexpected '/'.");
            }
        }
        return new Rule(include, parts);
    }

    private static boolean hasUnescapedSlash(String regex) {
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                i++;
            } else if (c == '/') {
                return true;
            }
        }
        return false;
    }

    /** Splits on {@code separator}, leaving alone what a {@code /regex/} part encloses. */
    private static List<String> splitOutsideRegex(String value, char separator) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inRegex = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && inRegex && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == '/') {
                inRegex = !inRegex;
            }
            if (c == separator && !inRegex) {
                pieces.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inRegex) {
            throw invalid("The data filter " + value + " is not valid: a regular expression is not closed.");
        }
        pieces.add(current.toString());
        return pieces;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }
}
