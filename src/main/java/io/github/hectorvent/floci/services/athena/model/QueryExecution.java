package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class QueryExecution {
    @JsonProperty("QueryExecutionId")
    private String queryExecutionId;
    @JsonProperty("Query")
    private String query;
    @JsonProperty("Status")
    private QueryExecutionStatus status;
    @JsonProperty("WorkGroup")
    private String workGroup;

    @JsonProperty("ResultConfiguration")
    private ResultConfiguration resultConfiguration;

    @JsonProperty("QueryExecutionContext")
    private QueryExecutionContext queryExecutionContext;

    public QueryExecution() {}
    public QueryExecution(String id, String query, String workGroup,
                          ResultConfiguration resultConfiguration,
                          QueryExecutionContext queryExecutionContext) {
        this.queryExecutionId = id;
        this.query = query;
        this.workGroup = workGroup;
        this.status = new QueryExecutionStatus(QueryExecutionState.QUEUED);
        this.resultConfiguration = resultConfiguration;
        this.queryExecutionContext = queryExecutionContext;
    }

    public String getQueryExecutionId() { return queryExecutionId; }
    public void setQueryExecutionId(String queryExecutionId) { this.queryExecutionId = queryExecutionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public QueryExecutionStatus getStatus() { return status; }
    public void setStatus(QueryExecutionStatus status) { this.status = status; }
    public String getWorkGroup() { return workGroup; }
    public void setWorkGroup(String workGroup) { this.workGroup = workGroup; }
    public ResultConfiguration getResultConfiguration() { return resultConfiguration; }
    public void setResultConfiguration(ResultConfiguration resultConfiguration) { this.resultConfiguration = resultConfiguration; }
    public QueryExecutionContext getQueryExecutionContext() { return queryExecutionContext; }
    public void setQueryExecutionContext(QueryExecutionContext queryExecutionContext) { this.queryExecutionContext = queryExecutionContext; }
}
