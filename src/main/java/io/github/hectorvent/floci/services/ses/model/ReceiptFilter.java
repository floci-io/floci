package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A SES v1 IP address filter. The wire shape nests Policy and Cidr under an IpFilter member;
 * the stored form is flat because the nesting carries no extra state.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceiptFilter {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Policy")
    private String policy;

    @JsonProperty("Cidr")
    private String cidr;

    public ReceiptFilter() {
    }

    public ReceiptFilter(String name, String policy, String cidr) {
        this.name = name;
        this.policy = policy;
        this.cidr = cidr;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getCidr() {
        return cidr;
    }

    public void setCidr(String cidr) {
        this.cidr = cidr;
    }
}
