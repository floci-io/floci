package io.github.hectorvent.floci.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record SpectrumExternalTable(
        String accountId,
        String databaseName,
        String schemaName,
        String tableName,
        List<SpectrumColumn> columns,
        String location,
        char delimiter,
        char quote,
        char escape,
        String nullValue,
        int headerLines) {

    public SpectrumExternalTable {
        requireText(accountId, "accountId");
        requireText(databaseName, "databaseName");
        requireText(schemaName, "schemaName");
        requireText(tableName, "tableName");
        SpectrumExternalSchema.validateS3Location(location);
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Spectrum external table requires columns");
        }
        columns = List.copyOf(columns);
        if (delimiter == '\0' || quote == '\0' || escape == '\0') {
            throw new IllegalArgumentException("Spectrum CSV delimiters must be valid characters");
        }
        if (nullValue == null) {
            throw new IllegalArgumentException("Spectrum nullValue is required");
        }
        if (headerLines < 0) {
            throw new IllegalArgumentException("Spectrum headerLines must not be negative");
        }
        for (int i = 0; i < columns.size(); i++) {
            String left = columns.get(i).name();
            for (int j = i + 1; j < columns.size(); j++) {
                if (left.equalsIgnoreCase(columns.get(j).name())) {
                    throw new IllegalArgumentException("Duplicate Spectrum column: " + left);
                }
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
