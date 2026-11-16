package io.floci.aws.services.redshift.spectrum;

/** What every external schema binding records about where its schema lives. */
interface SchemaBinding {
    String accountId();

    String clusterKey();

    String databaseName();

    String schemaName();
}
