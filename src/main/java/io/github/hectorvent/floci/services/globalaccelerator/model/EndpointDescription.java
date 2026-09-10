package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EndpointDescription {

    @JsonProperty("EndpointId")
    private String endpointId;

    @JsonProperty("Weight")
    private Integer weight;

    @JsonProperty("HealthState")
    private String healthState;

    @JsonProperty("ClientIPPreservationEnabled")
    private Boolean clientIpPreservationEnabled;

    public EndpointDescription() {}

    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public String getHealthState() { return healthState; }
    public void setHealthState(String healthState) { this.healthState = healthState; }

    public Boolean getClientIpPreservationEnabled() { return clientIpPreservationEnabled; }
    public void setClientIpPreservationEnabled(Boolean clientIpPreservationEnabled) {
        this.clientIpPreservationEnabled = clientIpPreservationEnabled;
    }
}
