package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortRange {

    @JsonProperty("FromPort")
    private Integer fromPort;

    @JsonProperty("ToPort")
    private Integer toPort;

    public PortRange() {}

    public Integer getFromPort() { return fromPort; }
    public void setFromPort(Integer fromPort) { this.fromPort = fromPort; }

    public Integer getToPort() { return toPort; }
    public void setToPort(Integer toPort) { this.toPort = toPort; }
}
