package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataCellsFilter {
    @JsonProperty("DatabaseName")
    private String databaseName;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("TableCatalogId")
    private String tableCatalogId;
    @JsonProperty("TableName")
    private String tableName;
    @JsonProperty("ColumnNames")
    private List<String> columnNames;
    @JsonProperty("ColumnWildcard")
    private ColumnWildcard columnWildcard;
    @JsonProperty("RowFilter")
    private RowFilter rowFilter;
    @JsonProperty("VersionId")
    private String versionId;

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

    public RowFilter getRowFilter() {
        return rowFilter;
    }

    public void setRowFilter(RowFilter rowFilter) {
        this.rowFilter = rowFilter;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public static class ColumnWildcard {
        @JsonProperty("ExcludedColumnNames")
        private List<String> excludedColumnNames;

        public List<String> getExcludedColumnNames() {
            return excludedColumnNames;
        }

        public void setExcludedColumnNames(List<String> excludedColumnNames) {
            this.excludedColumnNames = excludedColumnNames;
        }
    }

    public static class RowFilter {
        @JsonProperty("AllRowsWildcard")
        private AllRowsWildcard allRowsWildcard;
        @JsonProperty("FilterExpression")
        private String filterExpression;

        public AllRowsWildcard getAllRowsWildcard() {
            return allRowsWildcard;
        }

        public void setAllRowsWildcard(AllRowsWildcard allRowsWildcard) {
            this.allRowsWildcard = allRowsWildcard;
        }

        public String getFilterExpression() {
            return filterExpression;
        }

        public void setFilterExpression(String filterExpression) {
            this.filterExpression = filterExpression;
        }
    }

    public static class AllRowsWildcard {
    }
}
