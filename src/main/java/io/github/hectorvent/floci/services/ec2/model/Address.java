package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Address {

    private String allocationId;
    private String publicIp;
    private String domain = "vpc";
    private String instanceId;
    private String associationId;
    private String networkInterfaceId;
    private String privateIpAddress;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public Address() {}

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
