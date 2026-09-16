package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One {@code SubnetConfiguration} entry from {@code CreateVpcEndpoint} or
 * {@code ModifyVpcEndpoint}: the addresses to assign to the endpoint's network interface in a
 * given subnet.
 *
 * <p>{@code SubnetConfiguration} is request-only. No EC2 output shape carries it, so a caller
 * reads the addresses back from the endpoint's network interfaces rather than from the endpoint
 * itself, which is why this is stored on the endpoint and consumed when its interfaces are built.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_SubnetConfiguration.html">SubnetConfiguration</a>
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
