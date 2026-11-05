package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResultConfiguration {

    @JsonProperty("OutputLocation")
    private String outputLocation;

    public ResultConfiguration() {}

    public ResultConfiguration(String outputLocation) {
        this.outputLocation = outputLocation;
    }

    public String getOutputLocation() { return outputLocation; }
    public void setOutputLocation(String outputLocation) { this.outputLocation = outputLocation; }
}
