package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsnAssociation {
    private String asn;
    private String cidr;
    private String ipamId;
    private String state;
    private String statusMessage;

    public AsnAssociation() {}

    public AsnAssociation(String asn, String cidr, String ipamId, String state, String statusMessage) {
        this.asn = asn;
        this.cidr = cidr;
        this.ipamId = ipamId;
        this.state = state;
        this.statusMessage = statusMessage;
    }

    public String getAsn() { return asn; }
    public void setAsn(String asn) { this.asn = asn; }
    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }
    public String getIpamId() { return ipamId; }
    public void setIpamId(String ipamId) { this.ipamId = ipamId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}
