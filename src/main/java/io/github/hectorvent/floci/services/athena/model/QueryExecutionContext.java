package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class QueryExecutionContext {

    @JsonProperty("Database")
    private String database;

    @JsonProperty("Catalog")
    private String catalog;

    public QueryExecutionContext() {}

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getCatalog() { return catalog; }
    public void setCatalog(String catalog) { this.catalog = catalog; }
}
