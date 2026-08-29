package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Resource {
    private CatalogResource catalog;
    private DatabaseResource database;
    private DataCellsFilterResource dataCellsFilter;
    private DataLocationResource dataLocation;
    @JsonProperty("LFTag")
    private LFTagKeyResource lfTag;
    @JsonProperty("LFTagExpression")
    private LFTagExpressionResource lfTagExpression;
    @JsonProperty("LFTagPolicy")
    private LFTagPolicyResource lfTagPolicy;
    private TableResource table;
    private TableWithColumnsResource tableWithColumns;

    public CatalogResource getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogResource catalog) {
        this.catalog = catalog;
    }

    public DatabaseResource getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseResource database) {
        this.database = database;
    }

    public DataCellsFilterResource getDataCellsFilter() {
        return dataCellsFilter;
    }

    public void setDataCellsFilter(DataCellsFilterResource dataCellsFilter) {
        this.dataCellsFilter = dataCellsFilter;
    }

    public DataLocationResource getDataLocation() {
        return dataLocation;
    }

    public void setDataLocation(DataLocationResource dataLocation) {
        this.dataLocation = dataLocation;
    }

    public LFTagKeyResource getLfTag() {
        return lfTag;
    }

    public void setLfTag(LFTagKeyResource lfTag) {
        this.lfTag = lfTag;
    }

    public LFTagExpressionResource getLfTagExpression() {
        return lfTagExpression;
    }

    public void setLfTagExpression(LFTagExpressionResource lfTagExpression) {
        this.lfTagExpression = lfTagExpression;
    }

    public LFTagPolicyResource getLfTagPolicy() {
        return lfTagPolicy;
    }

    public void setLfTagPolicy(LFTagPolicyResource lfTagPolicy) {
        this.lfTagPolicy = lfTagPolicy;
    }

    public TableResource getTable() {
        return table;
    }

    public void setTable(TableResource table) {
        this.table = table;
    }

    public TableWithColumnsResource getTableWithColumns() {
        return tableWithColumns;
    }

    public void setTableWithColumns(TableWithColumnsResource tableWithColumns) {
        this.tableWithColumns = tableWithColumns;
    }
}
