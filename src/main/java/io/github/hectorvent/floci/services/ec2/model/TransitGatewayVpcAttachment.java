package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A transit gateway's attachment to a VPC. {@code DescribeTransitGatewayVpcAttachments} serves
 * this shape directly; {@code DescribeTransitGatewayAttachments} serves the resource-agnostic view
 * of the same record, which is where the route table association appears.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayVpcAttachment {

    private String transitGatewayAttachmentId;
    private String transitGatewayId;
    private String vpcId;
    private String vpcOwnerId;
    /** The gateway's owner, which is a different field from the VPC's even when they agree. */
    private String transitGatewayOwnerId;
    private String state;
    private List<String> subnetIds = new ArrayList<>();
    private String creationTime;
    private String region;
    private TransitGatewayVpcAttachmentOptions options = new TransitGatewayVpcAttachmentOptions();
    /** Set when the gateway associates attachments with its default route table. */
    private String associationRouteTableId;
    private String associationState;
    private List<Tag> tags = new ArrayList<>();

    public TransitGatewayVpcAttachment() {}

    public String getTransitGatewayAttachmentId() { return transitGatewayAttachmentId; }
    public void setTransitGatewayAttachmentId(String transitGatewayAttachmentId) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
    }

    public String getTransitGatewayId() { return transitGatewayId; }
    public void setTransitGatewayId(String transitGatewayId) { this.transitGatewayId = transitGatewayId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcOwnerId() { return vpcOwnerId; }
    public void setVpcOwnerId(String vpcOwnerId) { this.vpcOwnerId = vpcOwnerId; }

    public String getTransitGatewayOwnerId() { return transitGatewayOwnerId; }
    public void setTransitGatewayOwnerId(String transitGatewayOwnerId) {
        this.transitGatewayOwnerId = transitGatewayOwnerId;
    }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public TransitGatewayVpcAttachmentOptions getOptions() { return options; }
    public void setOptions(TransitGatewayVpcAttachmentOptions options) { this.options = options; }

    public String getAssociationRouteTableId() { return associationRouteTableId; }
    public void setAssociationRouteTableId(String associationRouteTableId) {
        this.associationRouteTableId = associationRouteTableId;
    }

    public String getAssociationState() { return associationState; }
    public void setAssociationState(String associationState) { this.associationState = associationState; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
