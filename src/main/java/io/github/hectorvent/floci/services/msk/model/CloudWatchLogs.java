package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudWatchLogs {

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("logGroup")
    private String logGroup;

    public CloudWatchLogs() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getLogGroup() { return logGroup; }
    public void setLogGroup(String logGroup) { this.logGroup = logGroup; }
}