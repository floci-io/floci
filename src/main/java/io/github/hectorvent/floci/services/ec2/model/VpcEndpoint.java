package io.github.hectorvent.floci.services.ec2.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a VPC Endpoint created via {@code CreateVpcEndpoint}.
 *
 * <p>floci adds VPC Endpoints (absent in upstream) so the emulated environment
 * can model PaaS access the way real AWS does. An <em>Interface</em> endpoint
 * (AWS PrivateLink) creates one elastic network interface (ENI) per subnet, each
 * with a private IP inside the VPC. Traffic from instances to the service (e.g.
 * S3) is then captured in VPC flow logs as a normal private-IP to private-IP
 * conversation whose destination is the endpoint ENI.</p>
 *
 * <p>Only an interface endpoint gives the service a private-IP ENI inside the
 * VPC, so traffic to it appears in VPC flow logs as a private-IP conversation.
 * Gateway endpoints (and public service access) route differently and have no
 * such per-subnet ENI, which is why this model focuses on the interface type.</p>
 */
public class VpcEndpoint {

    private String vpcEndpointId;
    private String vpcEndpointType = "Interface";   // Interface | Gateway | GatewayLoadBalancer
    private String serviceName;                       // e.g. com.amazonaws.us-east-1.s3
    private String vpcId;
    private String state = "available";
    private boolean privateDnsEnabled = true;
    private String ipAddressType = "ipv4";
    private String ownerId;
    private String region;
    private Instant creationTimestamp = Instant.now();

    private final List<String> subnetIds = new ArrayList<>();
    private final List<String> networkInterfaceIds = new ArrayList<>();
    private final List<String> routeTableIds = new ArrayList<>();
    private final List<GroupIdentifier> groups = new ArrayList<>();
    private final List<String> dnsNames = new ArrayList<>();
    private final List<Tag> tagSet = new ArrayList<>();

    public String getVpcEndpointId() { return vpcEndpointId; }
    public void setVpcEndpointId(String v) { this.vpcEndpointId = v; }

    public String getVpcEndpointType() { return vpcEndpointType; }
    public void setVpcEndpointType(String v) { this.vpcEndpointType = v; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String v) { this.serviceName = v; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }

    public String getState() { return state; }
    public void setState(String v) { this.state = v; }

    public boolean isPrivateDnsEnabled() { return privateDnsEnabled; }
    public void setPrivateDnsEnabled(boolean v) { this.privateDnsEnabled = v; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String v) { this.ipAddressType = v; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String v) { this.ownerId = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public Instant getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(Instant v) { this.creationTimestamp = v; }

    public List<String> getSubnetIds() { return subnetIds; }
    public List<String> getNetworkInterfaceIds() { return networkInterfaceIds; }
    public List<String> getRouteTableIds() { return routeTableIds; }
    public List<GroupIdentifier> getGroups() { return groups; }
    public List<String> getDnsNames() { return dnsNames; }
    public List<Tag> getTagSet() { return tagSet; }
}
