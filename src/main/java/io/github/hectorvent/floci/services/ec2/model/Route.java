package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Route {

    private String destinationCidrBlock;
    private String destinationIpv6CidrBlock;
    private String destinationPrefixListId;
    private String gatewayId;
    private String natGatewayId;
    private String egressOnlyInternetGatewayId;
    private String state = "active";
    private String origin;

    public Route() {}

    public Route(String destinationCidrBlock, String gatewayId, String origin) {
        this.destinationCidrBlock = destinationCidrBlock;
        this.gatewayId = gatewayId;
        this.origin = origin;
    }

    public String getDestinationCidrBlock() { return destinationCidrBlock; }
    public void setDestinationCidrBlock(String destinationCidrBlock) { this.destinationCidrBlock = destinationCidrBlock; }

    public String getDestinationIpv6CidrBlock() { return destinationIpv6CidrBlock; }
    public void setDestinationIpv6CidrBlock(String destinationIpv6CidrBlock) { this.destinationIpv6CidrBlock = destinationIpv6CidrBlock; }

    public String getDestinationPrefixListId() { return destinationPrefixListId; }
    public void setDestinationPrefixListId(String destinationPrefixListId) { this.destinationPrefixListId = destinationPrefixListId; }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getEgressOnlyInternetGatewayId() { return egressOnlyInternetGatewayId; }
    public void setEgressOnlyInternetGatewayId(String egressOnlyInternetGatewayId) { this.egressOnlyInternetGatewayId = egressOnlyInternetGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
