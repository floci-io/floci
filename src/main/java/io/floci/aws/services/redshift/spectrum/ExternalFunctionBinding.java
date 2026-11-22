package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A scalar Lambda UDF created with {@code CREATE EXTERNAL FUNCTION}. The backend holds a stub function of the same
 * signature; this binding keeps what Redshift records about it and PostgreSQL has no place for.
 */
@RegisterForReflection
public record ExternalFunctionBinding(String accountId, String clusterKey, String databaseName, String schemaName,
                                      String functionName, List<String> argumentTypes, String returnType,
                                      String volatility, String lambdaFunction, String iamRoleArn,
                                      long retryTimeoutMillis, int maxBatchRows, long maxBatchSizeBytes)
        implements SchemaBinding {
    public ExternalFunctionBinding {
        SchemaBinding.require(accountId, "accountId");
        SchemaBinding.require(clusterKey, "clusterKey");
        SchemaBinding.require(databaseName, "databaseName");
        SchemaBinding.require(schemaName, "schemaName");
        SchemaBinding.require(functionName, "functionName");
        SchemaBinding.require(returnType, "returnType");
        SchemaBinding.require(lambdaFunction, "lambdaFunction");
        SchemaBinding.require(iamRoleArn, "iamRoleArn");
        if (!"STABLE".equals(volatility) && !"VOLATILE".equals(volatility)) {
            throw new IllegalArgumentException("volatility must be STABLE or VOLATILE");
        }
        argumentTypes = List.copyOf(argumentTypes);
    }

    /** Keys the binding by its signature, as PostgreSQL does, so overloads of one name are separate functions. */
    @Override
    public String bindingName() {
        return nameOf(schemaName, functionName, argumentTypes);
    }

    public static String nameOf(String schemaName, String functionName, List<String> argumentTypes) {
        return schemaName + "." + functionName + "(" + String.join(",", argumentTypes) + ")";
    }
}
