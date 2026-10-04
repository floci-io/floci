package io.github.hectorvent.floci.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ExternalSchemaBinding(String accountId, String clusterKey, String databaseName,
                                   String schemaName, String glueDatabase, String iamRoleArn) {
    public ExternalSchemaBinding {
        require(accountId, "accountId");
        require(clusterKey, "clusterKey");
        require(databaseName, "databaseName");
        require(schemaName, "schemaName");
        require(glueDatabase, "glueDatabase");
        require(iamRoleArn, "iamRoleArn");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
