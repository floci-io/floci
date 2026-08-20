package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DataCellsFilterResource {
    private String databaseName;
    private String name;
    private String tableCatalogId;
    private String tableName;

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

    public String getTableCatalogId() {
        return tableCatalogId;
    }

    public void setTableCatalogId(String tableCatalogId) {
        this.tableCatalogId = tableCatalogId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
