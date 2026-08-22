package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class TableWithColumnsResource {
    private String catalogId;
    private List<String> columnNames;
    private ColumnWildcard columnWildcard;
    private String databaseName;
    private String name;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    public ColumnWildcard getColumnWildcard() {
        return columnWildcard;
    }

    public void setColumnWildcard(ColumnWildcard columnWildcard) {
        this.columnWildcard = columnWildcard;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
