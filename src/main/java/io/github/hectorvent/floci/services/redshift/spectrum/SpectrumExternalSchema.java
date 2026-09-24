package io.github.hectorvent.floci.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record SpectrumExternalSchema(
        String accountId,
        String databaseName,
        String schemaName,
        String location,
        String iamRoleArn) {

    public SpectrumExternalSchema {
        requireText(accountId, "accountId");
        requireText(databaseName, "databaseName");
        requireText(schemaName, "schemaName");
        validateS3Location(location);
        if (iamRoleArn != null && iamRoleArn.isBlank()) {
            throw new IllegalArgumentException("iamRoleArn must not be blank");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    static void validateS3Location(String value) {
        if (value == null || !value.startsWith("s3://") || value.length() <= "s3://".length()) {
            throw new IllegalArgumentException("Spectrum location must be an s3:// URI");
        }
        String path = value.substring("s3://".length());
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalArgumentException("Spectrum location must include a bucket and key or prefix");
        }
    }
}
