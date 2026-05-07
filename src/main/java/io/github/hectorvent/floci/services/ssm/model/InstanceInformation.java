package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceInformation {

    private String instanceId;
    private String agentName = "amazon-ssm-agent";
    private String agentVersion = "3.2.2172.0";
    private String pingStatus = "Online";
    private Instant lastPingDateTime;
    private String platformType;
    private String platformName;
    private String platformVersion;
    private String ipAddress;
    private String computerName;
    private String resourceType = "EC2Instance";
    private Instant registrationDate;
    private String region;

    public InstanceInformation() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getPingStatus() { return pingStatus; }
    public void setPingStatus(String pingStatus) { this.pingStatus = pingStatus; }

    public Instant getLastPingDateTime() { return lastPingDateTime; }
    public void setLastPingDateTime(Instant lastPingDateTime) { this.lastPingDateTime = lastPingDateTime; }

    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }

    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getComputerName() { return computerName; }
    public void setComputerName(String computerName) { this.computerName = computerName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public Instant getRegistrationDate() { return registrationDate; }
    public void setRegistrationDate(Instant registrationDate) { this.registrationDate = registrationDate; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
