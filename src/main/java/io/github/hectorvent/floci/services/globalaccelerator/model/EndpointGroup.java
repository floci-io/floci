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
public class EndpointGroup {

    @JsonProperty("EndpointGroupArn")
    private String endpointGroupArn;

    @JsonProperty("EndpointGroupRegion")
    private String endpointGroupRegion;

    @JsonProperty("EndpointDescriptions")
    private List<EndpointDescription> endpointDescriptions = new ArrayList<>();

    @JsonProperty("TrafficDialPercentage")
    private Float trafficDialPercentage;

    @JsonProperty("HealthCheckPort")
    private Integer healthCheckPort;

    @JsonProperty("HealthCheckProtocol")
    private String healthCheckProtocol;

    @JsonProperty("HealthCheckPath")
    private String healthCheckPath;

    @JsonProperty("HealthCheckIntervalSeconds")
    private Integer healthCheckIntervalSeconds;

    @JsonProperty("ThresholdCount")
    private Integer thresholdCount;

    @JsonProperty("PortOverrides")
    private List<PortOverride> portOverrides = new ArrayList<>();

    public EndpointGroup() {}

    public String getEndpointGroupArn() { return endpointGroupArn; }
    public void setEndpointGroupArn(String endpointGroupArn) { this.endpointGroupArn = endpointGroupArn; }

    public String getEndpointGroupRegion() { return endpointGroupRegion; }
    public void setEndpointGroupRegion(String endpointGroupRegion) { this.endpointGroupRegion = endpointGroupRegion; }

    public List<EndpointDescription> getEndpointDescriptions() { return endpointDescriptions; }
    public void setEndpointDescriptions(List<EndpointDescription> endpointDescriptions) {
        this.endpointDescriptions = endpointDescriptions != null ? endpointDescriptions : new ArrayList<>();
    }

    public Float getTrafficDialPercentage() { return trafficDialPercentage; }
    public void setTrafficDialPercentage(Float trafficDialPercentage) { this.trafficDialPercentage = trafficDialPercentage; }

    public Integer getHealthCheckPort() { return healthCheckPort; }
    public void setHealthCheckPort(Integer healthCheckPort) { this.healthCheckPort = healthCheckPort; }

    public String getHealthCheckProtocol() { return healthCheckProtocol; }
    public void setHealthCheckProtocol(String healthCheckProtocol) { this.healthCheckProtocol = healthCheckProtocol; }

    public String getHealthCheckPath() { return healthCheckPath; }
    public void setHealthCheckPath(String healthCheckPath) { this.healthCheckPath = healthCheckPath; }

    public Integer getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(Integer healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }

    public Integer getThresholdCount() { return thresholdCount; }
    public void setThresholdCount(Integer thresholdCount) { this.thresholdCount = thresholdCount; }

    public List<PortOverride> getPortOverrides() { return portOverrides; }
    public void setPortOverrides(List<PortOverride> portOverrides) {
        this.portOverrides = portOverrides != null ? portOverrides : new ArrayList<>();
    }
}
