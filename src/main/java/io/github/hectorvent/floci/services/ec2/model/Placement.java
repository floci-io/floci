package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Placement {

    private String availabilityZone;
    private String tenancy = "default";
    private String groupName;
    // lex00/floci#119: aws_launch_template's placement block only round-tripped
    // availabilityZone/tenancy/groupName; these extra fields are real,
    // documented members of LaunchTemplatePlacement(Request) that a launch
    // template can set independently of an actual running instance.
    private String hostId;
    private String affinity;
    private Integer partitionNumber;
    private String hostResourceGroupArn;
    private String spreadDomain;
    private String availabilityZoneId;
    private String groupId;

    public Placement() {}

    public Placement(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getTenancy() { return tenancy; }
    public void setTenancy(String tenancy) { this.tenancy = tenancy; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }

    public String getAffinity() { return affinity; }
    public void setAffinity(String affinity) { this.affinity = affinity; }

    public Integer getPartitionNumber() { return partitionNumber; }
    public void setPartitionNumber(Integer partitionNumber) { this.partitionNumber = partitionNumber; }

    public String getHostResourceGroupArn() { return hostResourceGroupArn; }
    public void setHostResourceGroupArn(String hostResourceGroupArn) { this.hostResourceGroupArn = hostResourceGroupArn; }

    public String getSpreadDomain() { return spreadDomain; }
    public void setSpreadDomain(String spreadDomain) { this.spreadDomain = spreadDomain; }

    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public boolean isEmpty() {
        return availabilityZone == null && groupName == null && hostId == null && affinity == null
                && partitionNumber == null && hostResourceGroupArn == null && spreadDomain == null
                && availabilityZoneId == null && groupId == null
                && (tenancy == null || "default".equals(tenancy));
    }
}
