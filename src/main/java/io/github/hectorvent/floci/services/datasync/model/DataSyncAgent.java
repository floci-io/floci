package io.github.hectorvent.floci.services.datasync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DataSyncAgent implements DataSyncTaggable {

    private String agentArn;
    private String name;
    private String status;
    private String activationKey;
    private String endpointType;
    private String vpcEndpointId;
    private List<String> subnetArns = new ArrayList<>();
    private List<String> securityGroupArns = new ArrayList<>();
    private String platformVersion;
    private Instant creationTime;
    private Instant lastConnectionTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getAgentArn() {
        return agentArn;
    }

    public void setAgentArn(String agentArn) {
        this.agentArn = agentArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getActivationKey() {
        return activationKey;
    }

    public void setActivationKey(String activationKey) {
        this.activationKey = activationKey;
    }

    public String getEndpointType() {
        return endpointType;
    }

    public void setEndpointType(String endpointType) {
        this.endpointType = endpointType;
    }

    public String getVpcEndpointId() {
        return vpcEndpointId;
    }

    public void setVpcEndpointId(String vpcEndpointId) {
        this.vpcEndpointId = vpcEndpointId;
    }

    public List<String> getSubnetArns() {
        return subnetArns;
    }

    public void setSubnetArns(List<String> subnetArns) {
        this.subnetArns = subnetArns != null ? subnetArns : new ArrayList<>();
    }

    public List<String> getSecurityGroupArns() {
        return securityGroupArns;
    }

    public void setSecurityGroupArns(List<String> securityGroupArns) {
        this.securityGroupArns = securityGroupArns != null ? securityGroupArns : new ArrayList<>();
    }

    public String getPlatformVersion() {
        return platformVersion;
    }

    public void setPlatformVersion(String platformVersion) {
        this.platformVersion = platformVersion;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public Instant getLastConnectionTime() {
        return lastConnectionTime;
    }

    public void setLastConnectionTime(Instant lastConnectionTime) {
        this.lastConnectionTime = lastConnectionTime;
    }

    @Override
    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    @Override
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
