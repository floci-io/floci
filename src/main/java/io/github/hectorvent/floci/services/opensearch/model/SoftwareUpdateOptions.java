package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Whether the domain takes service software updates on its own. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SoftwareUpdateOptions {

    @JsonProperty("AutoSoftwareUpdateEnabled")
    private Boolean autoSoftwareUpdateEnabled;

    public SoftwareUpdateOptions() {}

    public SoftwareUpdateOptions(Boolean autoSoftwareUpdateEnabled) {
        this.autoSoftwareUpdateEnabled = autoSoftwareUpdateEnabled;
    }

    public Boolean getAutoSoftwareUpdateEnabled() { return autoSoftwareUpdateEnabled; }
    public void setAutoSoftwareUpdateEnabled(Boolean enabled) { this.autoSoftwareUpdateEnabled = enabled; }
}
