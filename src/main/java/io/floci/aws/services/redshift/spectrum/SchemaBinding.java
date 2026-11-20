package io.floci.aws.services.redshift.spectrum;

/** What every external schema binding records about where its schema lives. */
interface SchemaBinding {
    String accountId();

    String clusterKey();

    String databaseName();

    String schemaName();

    /** The name the registry keys the binding by; a binding of something inside a schema names it too. */
    default String bindingName() {
        return schemaName();
    }
}
