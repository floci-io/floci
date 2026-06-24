package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkAcl {

    private String networkAclId;
    private String vpcId;
    private String ownerId;
    private String region;
    private boolean isDefault;
    private List<NetworkAclEntry> entries = new ArrayList<>();
    private List<NetworkAclAssociation> associations = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public NetworkAcl() {}

    public String getNetworkAclId() { return networkAclId; }
    public void setNetworkAclId(String networkAclId) { this.networkAclId = networkAclId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public List<NetworkAclEntry> getEntries() { return entries; }
    public void setEntries(List<NetworkAclEntry> entries) { this.entries = entries; }

    public List<NetworkAclAssociation> getAssociations() { return associations; }
    public void setAssociations(List<NetworkAclAssociation> associations) { this.associations = associations; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
