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

    /**
     * The columns {@code query} projects from {@code table}, in output order. {@link SpectrumQueryClassifier}
     * only ever accepts {@code *} or a comma-separated list of plain (optionally schema-qualified)
     * identifiers as a projection, so this is a complete, exact mapping, not a heuristic: it is what
     * an Extended Query Describe response is built from without a backend round trip.
     */
    public static List<SpectrumColumn> outputColumns(SpectrumQuery query, SpectrumExternalTable table) {
        if (query.selectStar()) {
            return table.columns();
        }
        List<SpectrumColumn> columns = new ArrayList<>();
        for (String part : query.projectionSql().split(",")) {
            String name = normalizeIdentifier(part.trim());
            columns.add(table.columns().stream()
                    .filter(column -> column.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new SpectrumSqlException("42703",
                            "column \"" + name + "\" does not exist on external table")));
        }
        return columns;
    }

    private static String normalizeIdentifier(String value) {
        String local = value.substring(value.lastIndexOf('.') + 1);
        if (local.length() >= 2 && local.startsWith("\"") && local.endsWith("\"")) {
            return local.substring(1, local.length() - 1).replace("\"\"", "\"");
        }
        return local.toLowerCase(Locale.ROOT);
    }
}
