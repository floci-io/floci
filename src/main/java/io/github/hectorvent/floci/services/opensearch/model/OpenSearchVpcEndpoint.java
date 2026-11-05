package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An OpenSearch Service-managed VPC endpoint ({@code CreateVpcEndpoint}): a way into a VPC domain
 * from another VPC. Floci keeps the record; no network interface is created.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenSearchVpcEndpoint {

    @JsonProperty("VpcEndpointId")
    private String vpcEndpointId;

    @JsonProperty("VpcEndpointOwner")
    private String vpcEndpointOwner;

    @JsonProperty("DomainArn")
    private String domainArn;

    /** Subnets and security groups as requested, with the VPC and zones EC2 knows them by. */
    @JsonProperty("VpcOptions")
    private VpcOptions vpcOptions = new VpcOptions();

    @JsonProperty("EgressEnabled")
    private Boolean egressEnabled;

    @JsonProperty("Status")
    private String status;

    /** Scopes a retried {@code CreateVpcEndpoint} to the endpoint it already created. */
    @JsonProperty("ClientToken")
    private String clientToken;

    public String getVpcEndpointId() { return vpcEndpointId; }
    public void setVpcEndpointId(String vpcEndpointId) { this.vpcEndpointId = vpcEndpointId; }

    public String getVpcEndpointOwner() { return vpcEndpointOwner; }
    public void setVpcEndpointOwner(String vpcEndpointOwner) { this.vpcEndpointOwner = vpcEndpointOwner; }

    public String getDomainArn() { return domainArn; }
    public void setDomainArn(String domainArn) { this.domainArn = domainArn; }

    public VpcOptions getVpcOptions() { return vpcOptions; }
    public void setVpcOptions(VpcOptions vpcOptions) { this.vpcOptions = vpcOptions; }

    public Boolean getEgressEnabled() { return egressEnabled; }
    public void setEgressEnabled(Boolean egressEnabled) { this.egressEnabled = egressEnabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
