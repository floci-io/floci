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
        require(accountId, "accountId");
        require(clusterKey, "clusterKey");
        require(databaseName, "databaseName");
        require(schemaName, "schemaName");
        require(iamRoleArn, "iamRoleArn");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
