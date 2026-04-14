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

    public QueryExecution() {}
    public QueryExecution(String id, String query, String workGroup) {
        this.queryExecutionId = id;
        this.query = query;
        this.workGroup = workGroup;
        this.status = new QueryExecutionStatus(QueryExecutionState.QUEUED);
    }

    public String getQueryExecutionId() { return queryExecutionId; }
    public void setQueryExecutionId(String queryExecutionId) { this.queryExecutionId = queryExecutionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public QueryExecutionStatus getStatus() { return status; }
    public void setStatus(QueryExecutionStatus status) { this.status = status; }
    public String getWorkGroup() { return workGroup; }
    public void setWorkGroup(String workGroup) { this.workGroup = workGroup; }
}
