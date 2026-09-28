package io.github.hectorvent.floci.services.redshift.spectrum;

public record SpectrumQuery(
        String schemaName,
        String tableName,
        String projectionSql,
        String predicateSql,
        boolean selectStar) {
}
