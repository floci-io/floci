package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * VPC placement options for an OpenSearch domain. Stored verbatim from the
 * {@code CreateDomain} / {@code UpdateDomainConfig} request and round-tripped on
 * {@code DescribeDomain} / {@code DescribeDomainConfig}. Floci does not actually
 * place containers inside the requested VPC — the domain still runs as a
 * single Docker container — but real SDK clients (Terraform, CDK, Pulumi) read
 * these fields back to confirm config and fail when they're empty.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcOptions {

    @JsonProperty("SubnetIds")
    private List<String> subnetIds = new ArrayList<>();

    @JsonProperty("SecurityGroupIds")
    private List<String> securityGroupIds = new ArrayList<>();

    @JsonProperty("AvailabilityZones")
    private List<String> availabilityZones = new ArrayList<>();

    @JsonProperty("VPCId")
    private String vpcId;

    public VpcOptions() {}

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public List<String> getAvailabilityZones() {
        return availabilityZones;
    }

    public void setAvailabilityZones(List<String> availabilityZones) {
        this.availabilityZones = availabilityZones != null ? new ArrayList<>(availabilityZones) : new ArrayList<>();
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }
}
