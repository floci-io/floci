package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpRange {

    private String cidrIp;
    private String description;

    public IpRange() {}

    public IpRange(String cidrIp) {
        this.cidrIp = cidrIp;
    }

    public IpRange(String cidrIp, String description) {
        this.cidrIp = cidrIp;
        this.description = description;
    }

    public String getCidrIp() { return cidrIp; }
    public void setCidrIp(String cidrIp) { this.cidrIp = cidrIp; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
