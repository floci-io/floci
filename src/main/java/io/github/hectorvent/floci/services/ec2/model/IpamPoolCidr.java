package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpamPoolCidr {

    private String cidr;
    private String state;
    /** Idempotency token of the ProvisionIpamPoolCidr call that created this CIDR. */
    private String clientToken;

    public IpamPoolCidr() {}

    public IpamPoolCidr(String cidr, String state) {
        this.cidr = cidr;
        this.state = state;
    }

    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
