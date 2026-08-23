package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One {@code SubnetConfiguration} entry from {@code CreateVpcEndpoint}: the private IP address(es)
 * AWS assigns to the endpoint's network interface in a given subnet. Per
 * {@code API_SubnetConfiguration.html}, the address is fixed at creation time and read back
 * unchanged on every subsequent describe.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcEndpointSubnetConfiguration {

    private String subnetId;
    private String ipv4;
    private String ipv6;

    public VpcEndpointSubnetConfiguration() {}

    public VpcEndpointSubnetConfiguration(String subnetId, String ipv4, String ipv6) {
        this.subnetId = subnetId;
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
    }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getIpv4() { return ipv4; }
    public void setIpv4(String ipv4) { this.ipv4 = ipv4; }

    public String getIpv6() { return ipv6; }
    public void setIpv6(String ipv6) { this.ipv6 = ipv6; }
}
