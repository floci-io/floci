package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** The VPC placement a job or crawler needs to reach a connection's data store. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhysicalConnectionRequirements {
    @JsonProperty("SubnetId")
    private String subnetId;

    @JsonProperty("SecurityGroupIdList")
    private List<String> securityGroupIdList;

    @JsonProperty("AvailabilityZone")
    private String availabilityZone;

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public List<String> getSecurityGroupIdList() { return securityGroupIdList; }
    public void setSecurityGroupIdList(List<String> securityGroupIdList) { this.securityGroupIdList = securityGroupIdList; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
}
