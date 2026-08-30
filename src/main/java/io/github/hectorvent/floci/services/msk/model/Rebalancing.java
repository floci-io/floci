package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Whether intelligent rebalancing is on. Status is PAUSED or ACTIVE. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rebalancing {

    @JsonProperty("status")
    private String status;

    public Rebalancing() {}

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
