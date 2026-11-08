package io.floci.aws.services.redshift.spectrum;

public record SpectrumQuery(
        String schemaName,
        String tableName,
        String projectionSql,
        String predicateSql,
        boolean selectStar) {
}
