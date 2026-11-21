package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ExternalSchemaBinding(String accountId, String clusterKey, String databaseName,
                                   String schemaName, String glueDatabase, String iamRoleArn)
        implements SchemaBinding {
    public ExternalSchemaBinding {
        SchemaBinding.require(accountId, "accountId");
        SchemaBinding.require(clusterKey, "clusterKey");
        SchemaBinding.require(databaseName, "databaseName");
        SchemaBinding.require(schemaName, "schemaName");
        SchemaBinding.require(glueDatabase, "glueDatabase");
        SchemaBinding.require(iamRoleArn, "iamRoleArn");
    }
}
