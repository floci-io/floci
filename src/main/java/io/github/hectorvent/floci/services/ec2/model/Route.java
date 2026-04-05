package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Route {

    private String destinationCidrBlock;
    private String gatewayId;
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

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
