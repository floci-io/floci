package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An external schema over Kinesis Data Streams. It is kept apart from {@link ExternalSchemaBinding} because it
 * has no Glue database, and the Spectrum read path treats every binding of that type as one.
 * {@code region} is null when the schema names none, which leaves the cluster's own Region.
 */
@RegisterForReflection
public record KinesisSchemaBinding(String accountId, String clusterKey, String databaseName,
                                   String schemaName, String region, String iamRoleArn)
        implements SchemaBinding {
    public KinesisSchemaBinding {
        SchemaBinding.require(accountId, "accountId");
        SchemaBinding.require(clusterKey, "clusterKey");
        SchemaBinding.require(databaseName, "databaseName");
        SchemaBinding.require(schemaName, "schemaName");
        SchemaBinding.require(iamRoleArn, "iamRoleArn");
    }
}
