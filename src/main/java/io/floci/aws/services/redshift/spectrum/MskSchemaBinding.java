package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An external schema over Amazon MSK. It is kept apart from {@link ExternalSchemaBinding} because it
 * has no Glue database, and the Spectrum read path treats every binding of that type as one.
 */
@RegisterForReflection
public record MskSchemaBinding(String accountId, String clusterKey, String databaseName,
                               String schemaName, String iamRoleArn,
                               String authentication, String clusterArn)
        implements SchemaBinding {
    public MskSchemaBinding {
        SchemaBinding.require(accountId, "accountId");
        SchemaBinding.require(clusterKey, "clusterKey");
        SchemaBinding.require(databaseName, "databaseName");
        SchemaBinding.require(schemaName, "schemaName");
        SchemaBinding.require(iamRoleArn, "iamRoleArn");
        SchemaBinding.require(authentication, "authentication");
        if (!"IAM".equals(authentication) && !"NONE".equals(authentication)) {
            throw new IllegalArgumentException("authentication must be IAM or NONE");
        }
        SchemaBinding.require(clusterArn, "clusterArn");
    }
}
