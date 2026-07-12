package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Minimal CloudWatch Logs Insights query engine.
 *
 * <p>Implements the subset of the Logs Insights query language that clients use in practice:
 * <ul>
 *   <li>{@code fields a, b, ...} — projection (defaults to {@code @timestamp, @message})</li>
 *   <li>{@code filter <field> = 'v'} / {@code filter <field> != 'v'} — equality / inequality</li>
 *   <li>{@code sort <field> [asc|desc]}</li>
 *   <li>{@code dedup <field, ...>} — keep the first row per unique tuple (applied after sort)</li>
 *   <li>{@code limit N}</li>
 * </ul>
 * Fields may be the synthetic {@code @timestamp} / {@code @message} / {@code @ingestionTime} /
 * {@code @ptr}, or a dotted path into the JSON log message (e.g. {@code params.job_id}, {@code level}).
 *
 * <p>This is intentionally <em>not</em> a full Insights implementation: unsupported commands
 * (e.g. {@code stats}, {@code parse}) are ignored with a warning. Pipes ({@code |}) inside quoted
 * filter values are respected and do not split the query into stages.
 */
final class LogsInsightsQuery {

    private static final Logger LOG = Logger.getLogger(LogsInsightsQuery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final List<String> fields = new ArrayList<>();
    private final List<Filter> filters = new ArrayList<>();
    private String sortField;
    private boolean sortDesc;
    private List<String> dedupFields;
    private Integer limit;

    private record Filter(String field, boolean negate, String value) {}

    private LogsInsightsQuery() {}

    static LogsInsightsQuery parse(String queryString) {
        LogsInsightsQuery q = new LogsInsightsQuery();
        if (queryString != null && !queryString.isBlank()) {
            for (String rawStage : splitStages(queryString)) {
                String stage = rawStage.trim();
                if (stage.isEmpty()) {
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
     * Split a query into pipeline stages on {@code |}, ignoring pipes inside single- or double-quoted
     * values (so {@code filter x = 'a|b'} stays one stage). Escaped quotes are not interpreted.
     */
    private static List<String> splitStages(String query) {
        List<String> stages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '|') {
                stages.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        stages.add(current.toString());
        return stages;
    }

    private void applyStage(String cmd, String args) {
        switch (cmd) {
            case "fields", "display" -> splitCsv(args, fields);
            case "filter", "where" -> {
                Filter f = parseFilter(args);
                if (f != null) {
                    filters.add(f);
                }
            }
            case "sort", "order" -> {
                String[] s = args.split("\\s+");
                if (s.length > 0 && !s[0].isEmpty()) {
                    sortField = s[0].trim();
                    sortDesc = s.length > 1 && s[1].trim().equalsIgnoreCase("desc");
                }
            }
            case "dedup" -> {
                dedupFields = new ArrayList<>();
                splitCsv(args, dedupFields);
            }
            case "limit" -> {
                try {
                    limit = Integer.parseInt(args.trim());
                } catch (NumberFormatException e) {
                    LOG.warnv("Ignoring invalid Logs Insights limit: {0}", args);
                }
            }
            default -> LOG.warnv("Ignoring unsupported Logs Insights command: {0}", cmd);
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

    private static Filter parseFilter(String expr) {
        // Scan for the operator OUTSIDE any quoted value, taking the leftmost one and checking
        // '!=' / '==' before '='. This avoids mistaking an operator inside a quoted literal
        // (e.g. filter x = 'a==b') for the real one.
        char quote = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (i > 0) {
                String op = expr.startsWith("!=", i) ? "!="
                        : expr.startsWith("==", i) ? "=="
                        : c == '=' ? "="
                        : null;
                if (op != null) {
                    String field = expr.substring(0, i).trim();
                    String value = unquote(expr.substring(i + op.length()).trim());
                    return new Filter(field, op.equals("!="), value);
                }
            }
        }
        LOG.warnv("Ignoring unsupported Logs Insights filter: {0}", expr);
        return null;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
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

        // filter (logical AND across all filter stages)
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
        for (Filter f : filters) {
            String actual = resolve(row, f.field());
            boolean equals = actual != null && actual.equals(f.value());
            boolean fails = f.negate() ? equals : !equals;
            if (fails) {
                return false;
            }
        }
        return true;
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
