package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TableResource {
    private String catalogId;
    private String databaseName;
    private String name;
    private TableWildcard tableWildcard;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
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

    public TableWildcard getTableWildcard() {
        return tableWildcard;
    }

    public void setTableWildcard(TableWildcard tableWildcard) {
        this.tableWildcard = tableWildcard;
    }
}
