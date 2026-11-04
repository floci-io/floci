package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkGroupConfiguration {

    @JsonProperty("ResultConfiguration")
    private ResultConfiguration resultConfiguration;

    @JsonProperty("ManagedQueryResultsConfiguration")
    private ManagedQueryResultsConfiguration managedQueryResultsConfiguration;

    @JsonProperty("EnforceWorkGroupConfiguration")
    private Boolean enforceWorkGroupConfiguration;

    @JsonProperty("PublishCloudWatchMetricsEnabled")
    private Boolean publishCloudWatchMetricsEnabled;

    @JsonProperty("RequesterPaysEnabled")
    private Boolean requesterPaysEnabled;

    @JsonProperty("BytesScannedCutoffPerQuery")
    private Long bytesScannedCutoffPerQuery;

    @JsonProperty("EngineVersion")
    private QueryExecution.EngineVersion engineVersion;

    @JsonProperty("EnableMinimumEncryptionConfiguration")
    private Boolean enableMinimumEncryptionConfiguration;

    public WorkGroupConfiguration() {}

    public ResultConfiguration getResultConfiguration() { return resultConfiguration; }
    public void setResultConfiguration(ResultConfiguration resultConfiguration) { this.resultConfiguration = resultConfiguration; }
    public Boolean getEnforceWorkGroupConfiguration() { return enforceWorkGroupConfiguration; }
    public void setEnforceWorkGroupConfiguration(Boolean enforceWorkGroupConfiguration) {
        this.enforceWorkGroupConfiguration = enforceWorkGroupConfiguration;
    }
    public Boolean getPublishCloudWatchMetricsEnabled() { return publishCloudWatchMetricsEnabled; }
    public void setPublishCloudWatchMetricsEnabled(Boolean publishCloudWatchMetricsEnabled) {
        this.publishCloudWatchMetricsEnabled = publishCloudWatchMetricsEnabled;
    }
    public Boolean getRequesterPaysEnabled() { return requesterPaysEnabled; }
    public void setRequesterPaysEnabled(Boolean requesterPaysEnabled) { this.requesterPaysEnabled = requesterPaysEnabled; }
    public Long getBytesScannedCutoffPerQuery() { return bytesScannedCutoffPerQuery; }
    public void setBytesScannedCutoffPerQuery(Long bytesScannedCutoffPerQuery) {
        this.bytesScannedCutoffPerQuery = bytesScannedCutoffPerQuery;
    }
    public ManagedQueryResultsConfiguration getManagedQueryResultsConfiguration() {
        return managedQueryResultsConfiguration;
    }
    public void setManagedQueryResultsConfiguration(ManagedQueryResultsConfiguration configuration) {
        this.managedQueryResultsConfiguration = configuration;
    }

    public Boolean getEnableMinimumEncryptionConfiguration() { return enableMinimumEncryptionConfiguration; }
    public void setEnableMinimumEncryptionConfiguration(Boolean enabled) {
        this.enableMinimumEncryptionConfiguration = enabled;
    }

    public QueryExecution.EngineVersion getEngineVersion() { return engineVersion; }
    public void setEngineVersion(QueryExecution.EngineVersion engineVersion) { this.engineVersion = engineVersion; }
}
