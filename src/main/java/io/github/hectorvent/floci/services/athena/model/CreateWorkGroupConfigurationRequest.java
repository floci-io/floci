package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class CreateWorkGroupConfigurationRequest {

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
    private WorkGroupEngineVersionRequest engineVersion;

    public CreateWorkGroupConfigurationRequest() {}

    public ResultConfiguration getResultConfiguration() { return resultConfiguration; }
    public void setResultConfiguration(ResultConfiguration resultConfiguration) {
        this.resultConfiguration = resultConfiguration;
    }
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
    public WorkGroupEngineVersionRequest getEngineVersion() { return engineVersion; }
    public void setEngineVersion(WorkGroupEngineVersionRequest engineVersion) { this.engineVersion = engineVersion; }
}
