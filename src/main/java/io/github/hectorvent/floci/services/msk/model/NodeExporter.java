package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeExporter {

    @JsonProperty("enabledInBroker")
    private Boolean enabledInBroker;

    public NodeExporter() {}

    public Boolean getEnabledInBroker() { return enabledInBroker; }
    public void setEnabledInBroker(Boolean enabledInBroker) { this.enabledInBroker = enabledInBroker; }
}
