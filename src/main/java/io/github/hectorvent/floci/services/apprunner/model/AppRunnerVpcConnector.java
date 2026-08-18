package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerVpcConnector {

    @JsonProperty("VpcConnectorName")
    private String vpcConnectorName;

    @JsonProperty("VpcConnectorArn")
    private String vpcConnectorArn;

    @JsonProperty("VpcConnectorRevision")
    private Integer vpcConnectorRevision;

    @JsonProperty("Subnets")
    private List<String> subnets = new ArrayList<>();

    @JsonProperty("SecurityGroups")
    private List<String> securityGroups = new ArrayList<>();

    @JsonProperty("Status")
    private String status;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("DeletedAt")
    private Long deletedAt;

    public AppRunnerVpcConnector() {}

    public String getVpcConnectorName() { return vpcConnectorName; }
    public void setVpcConnectorName(String vpcConnectorName) { this.vpcConnectorName = vpcConnectorName; }

    public String getVpcConnectorArn() { return vpcConnectorArn; }
    public void setVpcConnectorArn(String vpcConnectorArn) { this.vpcConnectorArn = vpcConnectorArn; }

    public Integer getVpcConnectorRevision() { return vpcConnectorRevision; }
    public void setVpcConnectorRevision(Integer vpcConnectorRevision) { this.vpcConnectorRevision = vpcConnectorRevision; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets != null ? subnets : new ArrayList<>(); }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups != null ? securityGroups : new ArrayList<>();
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
