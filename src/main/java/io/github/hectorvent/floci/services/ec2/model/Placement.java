package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Placement {

    private String availabilityZone;
    private String tenancy = "default";
    private String groupName;

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
}
