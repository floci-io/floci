package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpamPoolAllocation {

    private String ipamPoolAllocationId;
    private String cidr;
    private String description;
    private String resourceType;
    private String resourceId;
    private String resourceOwner;

    public IpamPoolAllocation() {}

    public String getIpamPoolAllocationId() { return ipamPoolAllocationId; }
    public void setIpamPoolAllocationId(String ipamPoolAllocationId) { this.ipamPoolAllocationId = ipamPoolAllocationId; }

    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceOwner() { return resourceOwner; }
    public void setResourceOwner(String resourceOwner) { this.resourceOwner = resourceOwner; }
}
