package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityGroup {

    private String groupId;
    private String groupName;
    private String description;
    private String vpcId;
    private String ownerId;
    private String region;
    private List<IpPermission> ipPermissions = new ArrayList<>();
    private List<IpPermission> ipPermissionsEgress = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public SecurityGroup() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<IpPermission> getIpPermissions() { return ipPermissions; }
    public void setIpPermissions(List<IpPermission> ipPermissions) { this.ipPermissions = ipPermissions; }

    public List<IpPermission> getIpPermissionsEgress() { return ipPermissionsEgress; }
    public void setIpPermissionsEgress(List<IpPermission> ipPermissionsEgress) { this.ipPermissionsEgress = ipPermissionsEgress; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
