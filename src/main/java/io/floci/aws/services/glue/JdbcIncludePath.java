package io.floci.aws.services.glue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The include path of a crawler's JDBC target for a database that has schemas: {@code database/schema/table}
 * or {@code database/schema}, where {@code %} stands for any run of characters in the schema or the table
 * but never in the database. A two-segment path takes every table of the schemas it names. The path is
 * case-sensitive. Exclusions are Unix-style globs matched against the object path relative to the include
 * path, from its first segment that holds a {@code %}: {@code dev/public/%} matches exclusions against the
 * table name, {@code dev/%} against {@code schema/table}.
 */
final class JdbcIncludePath {

    private final String database;
    private final Pattern schema;
    private final Pattern table;
    private final int literalSegments;
    private final List<Pattern> exclusions;

    private JdbcIncludePath(String database, Pattern schema, Pattern table, int literalSegments,
                            List<Pattern> exclusions) {
        this.database = database;
        this.schema = schema;
        this.table = table;
        this.literalSegments = literalSegments;
        this.exclusions = exclusions;
    }

    /** @throws IllegalArgumentException when the path is not {@code database/schema[/table]} */
    static JdbcIncludePath parse(String path, List<String> exclusions) {
        String[] segments = path == null ? new String[0] : path.split("/", -1);
        if (segments.length < 2 || segments.length > 3) {
            throw new IllegalArgumentException("Include path " + path
                    + " must be database/schema/table or database/schema.");
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Include path " + path + " has an empty segment.");
            }
        }
        if (segments[0].contains("%")) {
            throw new IllegalArgumentException("Include path " + path + " cannot use % for the database.");
        }
        String tableSegment = segments.length == 3 ? segments[2] : "%";
        int literalSegments = 1;
        if (!segments[1].contains("%")) {
            literalSegments = tableSegment.contains("%") ? 2 : 3;
        }
        List<Pattern> compiled = new ArrayList<>();
        if (exclusions != null) {
            for (String exclusion : exclusions) {
                compiled.add(glob(exclusion));
            }
        }
        return new JdbcIncludePath(segments[0], like(segments[1]), like(tableSegment), literalSegments,
                List.copyOf(compiled));
    }

    String database() {
        return database;
    }

    /** Whether the crawl takes {@code schema.table}: the include path names it and no exclusion removes it. */
    boolean selects(String schemaName, String tableName) {
        if (!schema.matcher(schemaName).matches() || !table.matcher(tableName).matches()) {
            return false;
        }
        String relative = literalSegments <= 1 ? schemaName + "/" + tableName : tableName;
        for (Pattern exclusion : exclusions) {
            if (exclusion.matcher(relative).matches()) {
                return false;
            }
        }
        return true;
    }

    private static Pattern like(String segment) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        int percent;
        while ((percent = segment.indexOf('%', start)) >= 0) {
            regex.append(Pattern.quote(segment.substring(start, percent))).append(".*");
            start = percent + 1;
        }
        regex.append(Pattern.quote(segment.substring(start)));
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * {@code *} matches within one path segment, {@code **} across segments, {@code ?} one character,
     * {@code [...]} a class ({@code !} negates), {@code {a,b}} a group, and a backslash escapes.
     */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean inGroup = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    } else {
                        regex.append(Pattern.quote("\\"));
                    }
                }
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        regex.append(Pattern.quote("["));
                    } else {
                        String body = glob.substring(i + 1, close);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        regex.append('[').append(body.replace("\\", "\\\\").replace("[", "\\[")).append(']');
                        i = close;
                    }
                }
                case '{' -> {
                    regex.append("(?:");
                    inGroup = true;
                }
                case '}' -> {
                    if (inGroup) {
                        regex.append(')');
                        inGroup = false;
                    } else {
                        regex.append(Pattern.quote("}"));
                    }
                }
                case ',' -> regex.append(inGroup ? "|" : ",");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (inGroup) {
            throw new IllegalArgumentException("Exclusion " + glob + " has an unclosed group.");
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
