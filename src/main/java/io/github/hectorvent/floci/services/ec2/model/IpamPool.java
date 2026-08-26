package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpamPool {

    private String ipamPoolId;
    private String ipamPoolArn;
    private String ipamId;
    private String ipamScopeId;
    private String ownerId;
    private String region;
    private String locale;
    private String sourceIpamPoolId;
    private String addressFamily;
    private String description;
    private String state;
    private boolean autoImport;
    private Integer allocationMinNetmaskLength;
    private Integer allocationMaxNetmaskLength;
    private Integer allocationDefaultNetmaskLength;
    private List<IpamPoolCidr> provisionedCidrs = new ArrayList<>();
    private List<IpamPoolAllocation> allocations = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();
    /** Idempotency token of the CreateIpamPool call that created this pool. */
    private String clientToken;

    public IpamPool() {}

    public String getIpamPoolId() { return ipamPoolId; }
    public void setIpamPoolId(String ipamPoolId) { this.ipamPoolId = ipamPoolId; }

    public String getIpamPoolArn() { return ipamPoolArn; }
    public void setIpamPoolArn(String ipamPoolArn) { this.ipamPoolArn = ipamPoolArn; }

    public String getIpamId() { return ipamId; }
    public void setIpamId(String ipamId) { this.ipamId = ipamId; }

    public String getIpamScopeId() { return ipamScopeId; }
    public void setIpamScopeId(String ipamScopeId) { this.ipamScopeId = ipamScopeId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public String getSourceIpamPoolId() { return sourceIpamPoolId; }
    public void setSourceIpamPoolId(String sourceIpamPoolId) { this.sourceIpamPoolId = sourceIpamPoolId; }

    public String getAddressFamily() { return addressFamily; }
    public void setAddressFamily(String addressFamily) { this.addressFamily = addressFamily; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public boolean isAutoImport() { return autoImport; }
    public void setAutoImport(boolean autoImport) { this.autoImport = autoImport; }

    public Integer getAllocationMinNetmaskLength() { return allocationMinNetmaskLength; }
    public void setAllocationMinNetmaskLength(Integer allocationMinNetmaskLength) {
        this.allocationMinNetmaskLength = allocationMinNetmaskLength;
    }

    public Integer getAllocationMaxNetmaskLength() { return allocationMaxNetmaskLength; }
    public void setAllocationMaxNetmaskLength(Integer allocationMaxNetmaskLength) {
        this.allocationMaxNetmaskLength = allocationMaxNetmaskLength;
    }

    public Integer getAllocationDefaultNetmaskLength() { return allocationDefaultNetmaskLength; }
    public void setAllocationDefaultNetmaskLength(Integer allocationDefaultNetmaskLength) {
        this.allocationDefaultNetmaskLength = allocationDefaultNetmaskLength;
    }

    public List<IpamPoolCidr> getProvisionedCidrs() { return provisionedCidrs; }
    public void setProvisionedCidrs(List<IpamPoolCidr> provisionedCidrs) { this.provisionedCidrs = provisionedCidrs; }

    public List<IpamPoolAllocation> getAllocations() { return allocations; }
    public void setAllocations(List<IpamPoolAllocation> allocations) { this.allocations = allocations; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
