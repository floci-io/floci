package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ResultConfigurationUpdates {

    @JsonProperty("OutputLocation")
    private String outputLocation;

    @JsonProperty("RemoveOutputLocation")
    private Boolean removeOutputLocation;

    public ResultConfigurationUpdates() {}

    public String getOutputLocation() { return outputLocation; }
    public void setOutputLocation(String outputLocation) { this.outputLocation = outputLocation; }
    public Boolean getRemoveOutputLocation() { return removeOutputLocation; }
    public void setRemoveOutputLocation(Boolean removeOutputLocation) {
        this.removeOutputLocation = removeOutputLocation;
    }
}
