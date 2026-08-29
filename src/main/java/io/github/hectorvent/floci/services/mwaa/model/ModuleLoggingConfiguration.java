package io.github.hectorvent.floci.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModuleLoggingConfiguration {

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("LogLevel")
    private String logLevel;

    @JsonProperty("CloudWatchLogGroupArn")
    private String cloudWatchLogGroupArn;

    public ModuleLoggingConfiguration() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

    public String getCloudWatchLogGroupArn() { return cloudWatchLogGroupArn; }
    public void setCloudWatchLogGroupArn(String cloudWatchLogGroupArn) { this.cloudWatchLogGroupArn = cloudWatchLogGroupArn; }
}
