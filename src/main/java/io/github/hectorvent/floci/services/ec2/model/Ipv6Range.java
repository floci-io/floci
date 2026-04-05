package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ipv6Range {

    private String cidrIpv6;
    private String description;

    public Ipv6Range() {}

    public Ipv6Range(String cidrIpv6) {
        this.cidrIpv6 = cidrIpv6;
    }

    public String getCidrIpv6() { return cidrIpv6; }
    public void setCidrIpv6(String cidrIpv6) { this.cidrIpv6 = cidrIpv6; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
