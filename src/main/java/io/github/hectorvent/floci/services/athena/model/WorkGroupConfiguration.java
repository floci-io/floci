package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WorkGroupConfiguration {

    @JsonProperty("ResultConfiguration")
    private ResultConfiguration resultConfiguration;

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
    public QueryExecution.EngineVersion getEngineVersion() { return engineVersion; }
    public void setEngineVersion(QueryExecution.EngineVersion engineVersion) { this.engineVersion = engineVersion; }
}
