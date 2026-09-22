package io.github.hectorvent.floci.services.redshift.spectrum;

public final class SpectrumQueryRewriter {

    private SpectrumQueryRewriter() {
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
}
