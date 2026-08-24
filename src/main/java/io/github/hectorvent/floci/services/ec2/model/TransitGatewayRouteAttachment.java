package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The attachment a transit gateway route resolves to. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayRouteAttachment {

    private String resourceId;
    private String transitGatewayAttachmentId;
    private String resourceType;

    public TransitGatewayRouteAttachment() {}

    public TransitGatewayRouteAttachment(String transitGatewayAttachmentId, String resourceId,
                                         String resourceType) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
        this.resourceId = resourceId;
        this.resourceType = resourceType;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getTransitGatewayAttachmentId() { return transitGatewayAttachmentId; }
    public void setTransitGatewayAttachmentId(String transitGatewayAttachmentId) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
    }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
}
