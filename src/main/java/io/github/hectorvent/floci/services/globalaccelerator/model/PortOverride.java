package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortOverride {

    @JsonProperty("ListenerPort")
    private Integer listenerPort;

    @JsonProperty("EndpointPort")
    private Integer endpointPort;

    public PortOverride() {}

    public Integer getListenerPort() { return listenerPort; }
    public void setListenerPort(Integer listenerPort) { this.listenerPort = listenerPort; }

    public Integer getEndpointPort() { return endpointPort; }
    public void setEndpointPort(Integer endpointPort) { this.endpointPort = endpointPort; }
}
