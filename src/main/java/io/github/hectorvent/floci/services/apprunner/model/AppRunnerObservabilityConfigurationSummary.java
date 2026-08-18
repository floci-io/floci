package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerObservabilityConfigurationSummary {

    @JsonProperty("ObservabilityConfigurationArn")
    private String observabilityConfigurationArn;

    @JsonProperty("ObservabilityConfigurationName")
    private String observabilityConfigurationName;

    @JsonProperty("ObservabilityConfigurationRevision")
    private Integer observabilityConfigurationRevision;

    public AppRunnerObservabilityConfigurationSummary() {}

    public static AppRunnerObservabilityConfigurationSummary of(AppRunnerObservabilityConfiguration configuration) {
        AppRunnerObservabilityConfigurationSummary summary = new AppRunnerObservabilityConfigurationSummary();
        summary.setObservabilityConfigurationArn(configuration.getObservabilityConfigurationArn());
        summary.setObservabilityConfigurationName(configuration.getObservabilityConfigurationName());
        summary.setObservabilityConfigurationRevision(configuration.getObservabilityConfigurationRevision());
        return summary;
    }

    public String getObservabilityConfigurationArn() { return observabilityConfigurationArn; }
    public void setObservabilityConfigurationArn(String observabilityConfigurationArn) {
        this.observabilityConfigurationArn = observabilityConfigurationArn;
    }

    public String getObservabilityConfigurationName() { return observabilityConfigurationName; }
    public void setObservabilityConfigurationName(String observabilityConfigurationName) {
        this.observabilityConfigurationName = observabilityConfigurationName;
    }

    public Integer getObservabilityConfigurationRevision() { return observabilityConfigurationRevision; }
    public void setObservabilityConfigurationRevision(Integer observabilityConfigurationRevision) {
        this.observabilityConfigurationRevision = observabilityConfigurationRevision;
    }
}
