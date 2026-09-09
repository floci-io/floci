package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A static route in a transit gateway route table.
 *
 * <p>Only static routes are stored. A propagated route is a view of an enabled propagation joined
 * to the attached VPC's CIDR, so it is derived when routes are searched rather than written down —
 * storing it would go stale the moment the VPC's CIDR associations change.
 *
 * <p>A blackhole route is a static route in the {@code blackhole} state rather than a type of its
 * own, and carries no attachment.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRoute {

    private String transitGatewayRouteTableId;
    private String destinationCidrBlock;
    private String transitGatewayAttachmentId;
    private String resourceId;
    private String resourceType;
    private String type;
    private String state;
    private String region;

    public TransitGatewayRoute() {}

    public String getTransitGatewayRouteTableId() { return transitGatewayRouteTableId; }
    public void setTransitGatewayRouteTableId(String transitGatewayRouteTableId) {
        this.transitGatewayRouteTableId = transitGatewayRouteTableId;
    }

    public String getDestinationCidrBlock() { return destinationCidrBlock; }
    public void setDestinationCidrBlock(String destinationCidrBlock) {
        this.destinationCidrBlock = destinationCidrBlock;
    }

    public String getTransitGatewayAttachmentId() { return transitGatewayAttachmentId; }
    public void setTransitGatewayAttachmentId(String transitGatewayAttachmentId) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
