package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An external schema over Amazon MSK. It is kept apart from {@link ExternalSchemaBinding} because it
 * has no Glue database, and the Spectrum read path treats every binding of that type as one.
 * {@code partition} is null when the statement omits {@code PARTITION}.
 */
@RegisterForReflection
public record MskSchemaBinding(String accountId, String clusterKey, String databaseName,
                               String schemaName, String partition, String iamRoleArn,
                               String authentication, String clusterArn)
        implements SchemaBinding {
    public MskSchemaBinding {
        require(accountId, "accountId");
        require(clusterKey, "clusterKey");
        require(databaseName, "databaseName");
        require(schemaName, "schemaName");
        require(iamRoleArn, "iamRoleArn");
        require(authentication, "authentication");
        if (!"IAM".equals(authentication) && !"NONE".equals(authentication)) {
            throw new IllegalArgumentException("authentication must be IAM or NONE");
        }
        require(clusterArn, "clusterArn");
        if (partition != null && partition.isBlank()) {
            throw new IllegalArgumentException("partition must not be blank");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
