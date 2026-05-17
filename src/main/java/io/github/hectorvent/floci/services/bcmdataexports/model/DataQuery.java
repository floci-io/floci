package io.github.hectorvent.floci.services.bcmdataexports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS BCM Data Exports {@code DataQuery}.
 * Carries the SQL query statement plus per-table configuration.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataQuery {

    private String queryStatement;
    private Map<String, Map<String, String>> tableConfigurations = new HashMap<>();

    public String getQueryStatement() { return queryStatement; }
    public void setQueryStatement(String v) { this.queryStatement = v; }

    public Map<String, Map<String, String>> getTableConfigurations() { return tableConfigurations; }
    public void setTableConfigurations(Map<String, Map<String, String>> v) { this.tableConfigurations = v; }
}
