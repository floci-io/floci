package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectivityInfo {

    @JsonProperty("publicAccess")
    private PublicAccess publicAccess;

    @JsonProperty("vpcConnectivity")
    private VpcConnectivity vpcConnectivity;

    @JsonProperty("networkType")
    private String networkType;

    public ConnectivityInfo() {}

    public PublicAccess getPublicAccess() { return publicAccess; }
    public void setPublicAccess(PublicAccess publicAccess) { this.publicAccess = publicAccess; }

    public VpcConnectivity getVpcConnectivity() { return vpcConnectivity; }
    public void setVpcConnectivity(VpcConnectivity vpcConnectivity) { this.vpcConnectivity = vpcConnectivity; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
}
