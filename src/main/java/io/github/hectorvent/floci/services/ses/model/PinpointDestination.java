package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PinpointDestination {

    @JsonProperty("ApplicationArn")
    private String applicationArn;

    public PinpointDestination() {}

    public String getApplicationArn() { return applicationArn; }
    public void setApplicationArn(String applicationArn) { this.applicationArn = applicationArn; }
}
