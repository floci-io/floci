package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.List;

public sealed interface SpectrumStatement permits SpectrumStatement.CreateSchema, SpectrumStatement.CreateTable {

    record CreateSchema(String schemaName, String databaseName, String iamRoleArn) implements SpectrumStatement {
    }

    record CreateTable(
            String schemaName,
            String tableName,
            List<SpectrumColumn> columns,
            String location,
            char delimiter,
            char quote,
            char escape,
            String nullValue,
            int headerLines) implements SpectrumStatement {

        public CreateTable {
            columns = List.copyOf(columns);
        }
    }
}
