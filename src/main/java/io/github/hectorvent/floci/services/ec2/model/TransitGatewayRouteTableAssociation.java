package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An attachment associated with a transit gateway route table. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRouteTableAssociation {

    private String transitGatewayAttachmentId;
    private String resourceId;
    private String resourceType;
    private String state = "associated";

    public TransitGatewayRouteTableAssociation() {}

    public TransitGatewayRouteTableAssociation(String transitGatewayAttachmentId, String resourceId,
                                               String resourceType, String state) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.state = state;
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
}
