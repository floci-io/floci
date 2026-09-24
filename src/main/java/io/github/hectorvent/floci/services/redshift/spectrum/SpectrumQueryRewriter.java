package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public final class SpectrumQueryRewriter {

    public SpectrumQueryRewriter() {
    }

    public static String rewrite(SpectrumQuery query, String materializedIdentifier) {
        if (materializedIdentifier == null || materializedIdentifier.isBlank()) {
            throw new IllegalArgumentException("materializedIdentifier is required");
        }
        String projection = query.selectStar() ? "*" : query.projectionSql();
        StringBuilder sql = new StringBuilder("SELECT ").append(projection)
                .append(" FROM \"").append(materializedIdentifier.replace("\"", "\"\""))
                .append('"');
        if (query.predicateSql() != null && !query.predicateSql().isBlank()) {
            sql.append(" WHERE ").append(query.predicateSql());
        }
        return sql.toString();
    }

    public static List<SpectrumColumn> outputColumns(SpectrumQuery query, SpectrumExternalTable table) {
        if (query.selectStar()) {
            return table.columns();
        }
        List<SpectrumColumn> columns = new ArrayList<>();
        for (String part : splitProjection(query.projectionSql())) {
            String name = normalizeIdentifier(part.trim());
            columns.add(table.columns().stream().filter(column -> column.name().equalsIgnoreCase(name))
                    .findFirst().orElseThrow(() -> new SpectrumSqlException("42703",
                            "column \"" + name + "\" does not exist on external table")));
        }
        return columns;
    }

    private static String normalizeIdentifier(String value) {
        int separator = lastUnquotedDot(value);
        String local = value.substring(separator + 1).trim();
        if (local.length() >= 2 && local.startsWith("\"") && local.endsWith("\"")) {
            return local.substring(1, local.length() - 1).replace("\"\"", "\"");
        }
        return local.toLowerCase(Locale.ROOT);
    }

    private static List<String> splitProjection(String projection) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < projection.length(); index++) {
            char current = projection.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < projection.length() && projection.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                parts.add(projection.substring(start, index).trim());
                start = index + 1;
            }
        }
        parts.add(projection.substring(start).trim());
        return parts;
    }

    private static int lastUnquotedDot(String identifier) {
        boolean quoted = false;
        int lastDot = -1;
        for (int index = 0; index < identifier.length(); index++) {
            char current = identifier.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < identifier.length() && identifier.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == '.' && !quoted) {
                lastDot = index;
            }
        }
        return lastDot;
    }
}
