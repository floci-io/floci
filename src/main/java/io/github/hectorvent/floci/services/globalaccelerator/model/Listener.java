package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Listener {

    @JsonProperty("ListenerArn")
    private String listenerArn;

    @JsonProperty("PortRanges")
    private List<PortRange> portRanges = new ArrayList<>();

    @JsonProperty("Protocol")
    private String protocol;

    @JsonProperty("ClientAffinity")
    private String clientAffinity;

    public Listener() {}

    public String getListenerArn() { return listenerArn; }
    public void setListenerArn(String listenerArn) { this.listenerArn = listenerArn; }

    public List<PortRange> getPortRanges() { return portRanges; }
    public void setPortRanges(List<PortRange> portRanges) {
        this.portRanges = portRanges != null ? portRanges : new ArrayList<>();
    }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getClientAffinity() { return clientAffinity; }
    public void setClientAffinity(String clientAffinity) { this.clientAffinity = clientAffinity; }
}
