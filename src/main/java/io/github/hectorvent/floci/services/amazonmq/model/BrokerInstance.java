package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A single broker instance's connection info, as returned inside
 * {@code DescribeBroker.BrokerInstances[]}. Clients read {@code Endpoints}
 * to connect (AMQP) and {@code ConsoleURL} to open the management UI.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrokerInstance {

    @JsonProperty("consoleURL")
    private String consoleURL;

    @JsonProperty("endpoints")
    private List<String> endpoints;

    @JsonProperty("ipAddress")
    private String ipAddress;

    public BrokerInstance() {}

    public BrokerInstance(String consoleURL, List<String> endpoints, String ipAddress) {
        this.consoleURL = consoleURL;
        this.endpoints = endpoints;
        this.ipAddress = ipAddress;
    }

    public String getConsoleURL() { return consoleURL; }
    public void setConsoleURL(String consoleURL) { this.consoleURL = consoleURL; }

    public List<String> getEndpoints() { return endpoints; }
    public void setEndpoints(List<String> endpoints) { this.endpoints = endpoints; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
}
