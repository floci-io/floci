package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AcceleratorAttributes {

    @JsonProperty("FlowLogsEnabled")
    private Boolean flowLogsEnabled;

    @JsonProperty("FlowLogsS3Bucket")
    private String flowLogsS3Bucket;

    @JsonProperty("FlowLogsS3Prefix")
    private String flowLogsS3Prefix;

    public AcceleratorAttributes() {}

    public Boolean getFlowLogsEnabled() { return flowLogsEnabled; }
    public void setFlowLogsEnabled(Boolean flowLogsEnabled) { this.flowLogsEnabled = flowLogsEnabled; }

    public String getFlowLogsS3Bucket() { return flowLogsS3Bucket; }
    public void setFlowLogsS3Bucket(String flowLogsS3Bucket) { this.flowLogsS3Bucket = flowLogsS3Bucket; }

    public String getFlowLogsS3Prefix() { return flowLogsS3Prefix; }
    public void setFlowLogsS3Prefix(String flowLogsS3Prefix) { this.flowLogsS3Prefix = flowLogsS3Prefix; }
}
