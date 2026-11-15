package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minimal CloudWatch Logs Insights query engine.
 *
 * <p>Implements the subset of the Logs Insights query language that clients use in practice:
 * <ul>
 *   <li>{@code fields a, b, ...} — projection (defaults to {@code @timestamp, @message})</li>
 *   <li>{@code filter <field> = 'v'} / {@code filter <field> != 'v'} — equality / inequality</li>
 *   <li>{@code filter a = 'x' or b = 'y'}: any comparison matches; {@code or} in any case, with parentheses</li>
 *   <li>{@code sort <field> [asc|desc]}</li>
 *   <li>{@code dedup <field, ...>} — keep the first row per unique tuple (applied after sort)</li>
 *   <li>{@code limit N}</li>
 * </ul>
 * Fields may be the synthetic {@code @timestamp} / {@code @message} / {@code @ingestionTime} /
 * {@code @ptr}, or a dotted path into the JSON log message (e.g. {@code params.job_id}, {@code level}).
 *
 * <p>This is intentionally <em>not</em> a full Insights implementation: unsupported commands
 * (e.g. {@code stats}, {@code parse}) and unsupported filter operators (e.g. {@code >=},
 * {@code like}) mark the query as unsupported (see {@link #isUnsupported()}) rather than being
 * silently dropped, so the caller can fail the query instead of returning a result set that does
 * not correspond to what was asked. Pipes ({@code |}) inside quoted filter values are respected
 * and do not split the query into stages.
 */
final class LogsInsightsQuery {

    private static final Logger LOG = Logger.getLogger(LogsInsightsQuery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    // Possessive, so a long dotted path is matched in a loop rather than one stack frame per segment.
    private static final Pattern FIELD = Pattern.compile("[@A-Za-z_]\\w*(?:\\.\\w+)*+");

    private final List<String> fields = new ArrayList<>();
    private final List<List<Filter>> filters = new ArrayList<>();
    private String sortField;
    private boolean sortDesc;
    private List<String> dedupFields;
    private Integer limit;
    private String unsupportedReason;

    private record Filter(String field, boolean negate, String value, boolean numeric) {}

    private LogsInsightsQuery() {}

    static LogsInsightsQuery parse(String queryString) {
        LogsInsightsQuery q = new LogsInsightsQuery();
        if (queryString != null) {
            for (String rawStage : splitStages(queryString)) {
                String stage = rawStage.trim();
                if (stage.isEmpty()) {
                    q.noteUnsupported("Empty query stage");
                    continue;
                }
                String[] parts = stage.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String args = parts.length > 1 ? parts[1].trim() : "";
                q.applyStage(cmd, args);
            }
        }
        if (q.fields.isEmpty()) {
            q.fields.add("@timestamp");
            q.fields.add("@message");
        }
        return q;
    }

    /**
     * Whether the query contains a command or filter expression this engine cannot evaluate.
     * Such a query must not be executed as if the unsupported stage were absent: the caller
     * (see {@link #getUnsupportedReason()}) should fail the query rather than return a result
     * set that silently omits it.
     */
    boolean isUnsupported() {
        return unsupportedReason != null;
    }

    /** The first unsupported command or filter expression encountered, or {@code null} if none. */
    String getUnsupportedReason() {
        return unsupportedReason;
    }

    /**
     * Split a query into pipeline stages on {@code |} outside quoted text (so {@code filter x = 'a|b'}
     * stays one stage).
     */
    private static List<String> splitStages(String query) {
        List<String> stages = new ArrayList<>();
        int start = 0;
        for (int i = unquoted(query, 0); i < query.length(); i = unquoted(query, i + 1)) {
            if (query.charAt(i) == '|') {
                stages.add(query.substring(start, i));
                start = i + 1;
            }
        }
        stages.add(query.substring(start));
        return stages;
    }

    /**
     * The first index at or after {@code from} (itself outside quoted text) that is outside single- or
     * double-quoted text and is not a quote itself, or {@code s.length()}. Escaped quotes are not
     * interpreted; an unclosed quote runs to the end.
     */
    private static int unquoted(String s, int from) {
        int i = from;
        while (i < s.length() && (s.charAt(i) == '\'' || s.charAt(i) == '"')) {
            int close = s.indexOf(s.charAt(i), i + 1);
            i = close < 0 ? s.length() : close + 1;
        }
        return i;
    }

    private void applyStage(String cmd, String args) {
        switch (cmd) {
            case "fields", "display" -> splitCsv(args, fields);
            case "filter", "where" -> {
                List<Filter> anyOf = args.indexOf('\\') < 0 ? parseDisjunction(args) : null;
                if (anyOf != null) {
                    filters.add(anyOf);
                } else {
                    noteUnsupported("Unsupported filter expression: " + args);
                }
            }
            case "sort", "order" -> {
                String[] s = args.split("\\s+");
                if (s.length > 0 && !s[0].isEmpty()) {
                    sortField = s[0].trim();
                    sortDesc = s.length > 1 && s[1].trim().equalsIgnoreCase("desc");
                } else {
                    noteUnsupported("Sort command is missing its field");
                }
            }
            case "dedup" -> {
                dedupFields = new ArrayList<>();
                splitCsv(args, dedupFields);
                if (dedupFields.isEmpty()) {
                    noteUnsupported("Dedup command is missing its field(s): " + args);
                }
            }
            case "limit" -> {
                try {
                    limit = Integer.parseInt(args.trim());
                } catch (NumberFormatException e) {
                    noteUnsupported("Invalid limit argument: " + args);
                }
            }
            default -> noteUnsupported("Unsupported command: " + cmd);
        }
    }

    /**
     * Record the first unsupported command or filter expression the query contains. Only the
     * first is kept: it is enough to fail the whole query, and later stages may reference fields
     * or state the earlier failure never let this engine establish.
     */
    private void noteUnsupported(String reason) {
        LOG.warnv("Logs Insights query contains unsupported syntax, failing the query: {0}", reason);
        if (unsupportedReason == null) {
            unsupportedReason = reason;
        }
    }

    private static void splitCsv(String args, List<String> target) {
        for (String token : args.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                target.add(t);
            }
        }
    }

    /**
     * Parse comparisons joined with {@code or} (any case), optionally grouped in parentheses.
     * Returns {@code null} when any term is not a single supported comparison.
     */
    private static List<Filter> parseDisjunction(String expr) {
        String body = stripGroup(expr.trim());
        List<String> terms = splitOr(body);
        if (terms.size() == 1) {
            Filter f = parseFilter(body);
            return f != null ? List.of(f) : null;
        }
        List<Filter> anyOf = new ArrayList<>();
        for (String term : terms) {
            List<Filter> part = parseDisjunction(term);
            if (part == null) {
                return null;
            }
            anyOf.addAll(part);
        }
        return anyOf;
    }

    /** Remove parentheses that enclose the whole expression. */
    private static String stripGroup(String expr) {
        String s = expr;
        while (s.startsWith("(") && closingParen(s) == s.length() - 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** Index of the parenthesis closing the one at index 0, ignoring quoted text, or -1. */
    private static int closingParen(String s) {
        int depth = 0;
        for (int i = unquoted(s, 0); i < s.length(); i = unquoted(s, i + 1)) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Split on the word {@code or} (any case) outside quotes and parentheses. */
    private static List<String> splitOr(String s) {
        List<String> terms = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = unquoted(s, 0); i < s.length(); i = unquoted(s, i + 1)) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && s.regionMatches(true, i, "or", 0, 2)
                    && (i == 0 || isDelimiter(s.charAt(i - 1)))
                    && (i + 2 == s.length() || isDelimiter(s.charAt(i + 2)))) {
                terms.add(s.substring(start, i));
                start = i + 2;
            }
        }
        terms.add(s.substring(start));
        return terms;
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '\'' || c == '"' || c == '(' || c == ')';
    }

    private static Filter parseFilter(String expr) {
        // Take the leftmost operator outside quoted text, checking '!=' / '==' before '=', so an
        // operator inside a quoted literal (e.g. filter x = 'a==b') is not mistaken for the real one.
        for (int i = unquoted(expr, 0); i < expr.length(); i = unquoted(expr, i + 1)) {
            char c = expr.charAt(i);
            if (expr.startsWith(">=", i) || expr.startsWith("<=", i) || expr.startsWith("=~", i)) {
                // The '=' in >=, <= and =~ would be read as equality below, mangling the field
                // (>=, <=) or the value (=~) into a dead match; stop so they warn instead.
                break;
            } else if (i > 0) {
                String op = expr.startsWith("!=", i) ? "!="
                        : expr.startsWith("==", i) ? "=="
                        : c == '=' ? "="
                        : null;
                if (op != null) {
                    String field = expr.substring(0, i).trim();
                    String raw = expr.substring(i + op.length()).trim();
                    String value = literal(raw);
                    if (value == null || !FIELD.matcher(field).matches()) {
                        return null;
                    }
                    return new Filter(field, op.equals("!="), value, NUMBER.matcher(raw).matches());
                }
            }
        }
        return null;
    }

    /**
     * The text of a quoted literal, or a bare number; {@code null} for anything else. AWS evaluates
     * other unquoted values (a word, {@code 1+1}) as expressions, which this engine does not support.
     */
    private static String literal(String s) {
        if (!s.isEmpty() && (s.charAt(0) == '\'' || s.charAt(0) == '"')) {
            int close = s.indexOf(s.charAt(0), 1);
            return close == s.length() - 1 ? s.substring(1, close) : null;
        }
        return NUMBER.matcher(s).matches() ? s : null;
    }

    /**
     * Evaluate the query against the given events.
     *
     * @return ordered result rows; each row maps a projected field name to its string value,
     *         in the order declared by {@code fields}, with a synthetic {@code @ptr} appended.
     */
    List<LinkedHashMap<String, String>> evaluate(List<LogEvent> events, int defaultLimit) {
        List<Row> rows = new ArrayList<>(events.size());
        for (LogEvent e : events) {
            rows.add(new Row(e, tryParse(e.getMessage())));
        }

        // filter (logical AND across filter stages, OR within one)
        List<Row> matched = new ArrayList<>();
        for (Row row : rows) {
            if (passesFilters(row)) {
                matched.add(row);
            }
        }

        // sort
        if (sortField != null) {
            Comparator<Row> cmp = comparatorFor(sortField);
            matched.sort(sortDesc ? cmp.reversed() : cmp);
        } else if (dedupFields != null && !dedupFields.isEmpty()) {
            // AWS: when dedup runs without an explicit preceding sort, results are ordered by the
            // default descending @timestamp sort before duplicates are discarded, so the first
            // (newest) row per tuple is the one kept. Reuse the @timestamp comparator reversed so
            // the sequence/eventId tie-break stays consistent with an explicit `sort @timestamp desc`.
            matched.sort(comparatorFor("@timestamp").reversed());
        }

        // dedup (keep first occurrence per tuple, after sorting)
        if (dedupFields != null && !dedupFields.isEmpty()) {
            List<Row> deduped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Row row : matched) {
                StringBuilder key = new StringBuilder();
                for (String df : dedupFields) {
                    key.append(resolve(row, df)).append('\0');
                }
                if (seen.add(key.toString())) {
                    deduped.add(row);
                }
            }
            matched = deduped;
        }

        // limit
        int max = limit != null ? Math.min(limit, defaultLimit) : defaultLimit;
        if (max >= 0 && matched.size() > max) {
            matched = matched.subList(0, max);
        }

        // project
        List<LinkedHashMap<String, String>> out = new ArrayList<>(matched.size());
        for (Row row : matched) {
            LinkedHashMap<String, String> projected = new LinkedHashMap<>();
            for (String field : fields) {
                String value = resolve(row, field);
                projected.put(field, value != null ? value : "");
            }
            projected.putIfAbsent("@ptr", row.event.getEventId());
            out.add(projected);
        }
        return out;
    }

    private boolean passesFilters(Row row) {
        for (List<Filter> anyOf : filters) {
            if (anyOf.stream().noneMatch(f -> matches(row, f))) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(Row row, Filter f) {
        String actual = resolve(row, f.field());
        boolean equal = actual != null && (f.numeric() ? sameNumber(actual, f.value()) : actual.equals(f.value()));
        return f.negate() != equal;
    }

    /** A bare number compares by value on AWS, so {@code n = 2} matches 2, 2.0 and 02. */
    private static boolean sameNumber(String actual, String expected) {
        try {
            return new BigDecimal(actual).compareTo(new BigDecimal(expected)) == 0;
        } catch (NumberFormatException ignored) {
            // A non-numeric field value is simply not equal to a number.
            return false;
        }
    }

    private Comparator<Row> comparatorFor(String field) {
        if ("@timestamp".equals(field)) {
            return Comparator.<Row>comparingLong(r -> r.event.getTimestamp())
                    .thenComparingLong(r -> r.event.getSequence())
                    .thenComparing(r -> r.event.getEventId());
        }
        if ("@ingestionTime".equals(field)) {
            // ingestionTime is one wall-clock value per PutLogEvents batch, so whole batches tie on it;
            // the sequence/eventId tie-break keeps same-batch events in a stable ingestion order.
            return Comparator.<Row>comparingLong(r -> r.event.getIngestionTime())
                    .thenComparingLong(r -> r.event.getSequence())
                    .thenComparing(r -> r.event.getEventId());
        }
        return Comparator.comparing(r -> {
            String v = resolve(r, field);
            return v != null ? v : "";
        });
    }

    private String resolve(Row row, String field) {
        switch (field) {
            case "@timestamp":
                return TS_FORMAT.format(Instant.ofEpochMilli(row.event.getTimestamp()));
            case "@ingestionTime":
                return TS_FORMAT.format(Instant.ofEpochMilli(row.event.getIngestionTime()));
            case "@message":
                return row.event.getMessage();
            case "@ptr":
                return row.event.getEventId();
            default:
                if (row.json == null) {
                    return null;
                }
                JsonNode node = row.json;
                for (String seg : field.split("\\.")) {
                    if (node == null) {
                        return null;
                    }
                    node = node.get(seg);
                }
                if (node == null || node.isNull()) {
                    return null;
                }
                return node.isValueNode() ? node.asText() : node.toString();
        }
    }

    private static JsonNode tryParse(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(message);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Row {
        final LogEvent event;
        final JsonNode json;

        Row(LogEvent event, JsonNode json) {
            this.event = event;
            this.json = json;
        }
    }
}
