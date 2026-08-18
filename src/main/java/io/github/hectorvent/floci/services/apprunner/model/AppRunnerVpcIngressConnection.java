package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerVpcIngressConnection {

    @JsonProperty("VpcIngressConnectionArn")
    private String vpcIngressConnectionArn;

    @JsonProperty("VpcIngressConnectionName")
    private String vpcIngressConnectionName;

    @JsonProperty("ServiceArn")
    private String serviceArn;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("DomainName")
    private String domainName;

    @JsonProperty("IngressVpcConfiguration")
    private JsonNode ingressVpcConfiguration;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("DeletedAt")
    private Long deletedAt;

    public AppRunnerVpcIngressConnection() {}

    public String getVpcIngressConnectionArn() { return vpcIngressConnectionArn; }
    public void setVpcIngressConnectionArn(String vpcIngressConnectionArn) {
        this.vpcIngressConnectionArn = vpcIngressConnectionArn;
    }

    public String getVpcIngressConnectionName() { return vpcIngressConnectionName; }
    public void setVpcIngressConnectionName(String vpcIngressConnectionName) {
        this.vpcIngressConnectionName = vpcIngressConnectionName;
    }

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public JsonNode getIngressVpcConfiguration() { return ingressVpcConfiguration; }
    public void setIngressVpcConfiguration(JsonNode ingressVpcConfiguration) {
        this.ingressVpcConfiguration = ingressVpcConfiguration;
    }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
