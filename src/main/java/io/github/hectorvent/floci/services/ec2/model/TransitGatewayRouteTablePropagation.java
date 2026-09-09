package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An attachment propagating its routes into a transit gateway route table.
 *
 * <p>Unlike an association, which lives on the attachment because an attachment has at most one,
 * a propagation is its own record: one attachment may propagate into several route tables.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRouteTablePropagation {

    private String transitGatewayRouteTableId;
    private String transitGatewayAttachmentId;
    private String resourceId;
    private String resourceType;
    private String state;
    private String region;

    public TransitGatewayRouteTablePropagation() {}

    public String getTransitGatewayRouteTableId() { return transitGatewayRouteTableId; }
    public void setTransitGatewayRouteTableId(String transitGatewayRouteTableId) {
        this.transitGatewayRouteTableId = transitGatewayRouteTableId;
    }

    public String getTransitGatewayAttachmentId() { return transitGatewayAttachmentId; }
    public void setTransitGatewayAttachmentId(String transitGatewayAttachmentId) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
