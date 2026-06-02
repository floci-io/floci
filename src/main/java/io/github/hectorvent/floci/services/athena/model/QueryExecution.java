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
    @JsonProperty("StatementType")
    private String statementType;
    @JsonProperty("Statistics")
    private QueryExecutionStatistics statistics;
    @JsonProperty("EngineVersion")
    private EngineVersion engineVersion;

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
        this.statementType = "DML";
        this.statistics = new QueryExecutionStatistics();
        this.engineVersion = new EngineVersion();
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
    public String getStatementType() { return statementType; }
    public void setStatementType(String statementType) { this.statementType = statementType; }
    public QueryExecutionStatistics getStatistics() { return statistics; }
    public void setStatistics(QueryExecutionStatistics statistics) { this.statistics = statistics; }
    public EngineVersion getEngineVersion() { return engineVersion; }
    public void setEngineVersion(EngineVersion engineVersion) { this.engineVersion = engineVersion; }

    @RegisterForReflection
    public static class QueryExecutionStatistics {
        @JsonProperty("EngineExecutionTimeInMillis")
        private Long engineExecutionTimeInMillis = 1L;
        @JsonProperty("DataScannedInBytes")
        private Long dataScannedInBytes = 0L;

        public Long getEngineExecutionTimeInMillis() { return engineExecutionTimeInMillis; }
        public void setEngineExecutionTimeInMillis(Long engineExecutionTimeInMillis) { this.engineExecutionTimeInMillis = engineExecutionTimeInMillis; }
        public Long getDataScannedInBytes() { return dataScannedInBytes; }
        public void setDataScannedInBytes(Long dataScannedInBytes) { this.dataScannedInBytes = dataScannedInBytes; }
    }

    @RegisterForReflection
    public static class EngineVersion {
        @JsonProperty("SelectedEngineVersion")
        private String selectedEngineVersion = "Athena engine version 3";
        @JsonProperty("EffectiveEngineVersion")
        private String effectiveEngineVersion = "Athena engine version 3";

        public String getSelectedEngineVersion() { return selectedEngineVersion; }
        public void setSelectedEngineVersion(String selectedEngineVersion) { this.selectedEngineVersion = selectedEngineVersion; }
        public String getEffectiveEngineVersion() { return effectiveEngineVersion; }
        public void setEffectiveEngineVersion(String effectiveEngineVersion) { this.effectiveEngineVersion = effectiveEngineVersion; }
    }
}
