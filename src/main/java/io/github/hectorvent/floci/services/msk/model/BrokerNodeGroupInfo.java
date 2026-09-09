package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrokerNodeGroupInfo {

    @JsonProperty("brokerAZDistribution")
    private String brokerAZDistribution;

    @JsonProperty("clientSubnets")
    private List<String> clientSubnets;

    @JsonProperty("instanceType")
    private String instanceType;

    @JsonProperty("securityGroups")
    private List<String> securityGroups;

    @JsonProperty("storageInfo")
    private StorageInfo storageInfo;

    @JsonProperty("connectivityInfo")
    private ConnectivityInfo connectivityInfo;

    @JsonProperty("zoneIds")
    private List<String> zoneIds;

    public BrokerNodeGroupInfo() {}

    public String getBrokerAZDistribution() { return brokerAZDistribution; }
    public void setBrokerAZDistribution(String brokerAZDistribution) { this.brokerAZDistribution = brokerAZDistribution; }

    public List<String> getClientSubnets() { return clientSubnets; }
    public void setClientSubnets(List<String> clientSubnets) { this.clientSubnets = clientSubnets; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) { this.securityGroups = securityGroups; }

    public StorageInfo getStorageInfo() { return storageInfo; }
    public void setStorageInfo(StorageInfo storageInfo) { this.storageInfo = storageInfo; }

    public ConnectivityInfo getConnectivityInfo() { return connectivityInfo; }
    public void setConnectivityInfo(ConnectivityInfo connectivityInfo) { this.connectivityInfo = connectivityInfo; }

    public List<String> getZoneIds() { return zoneIds; }
    public void setZoneIds(List<String> zoneIds) { this.zoneIds = zoneIds; }
}