package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayVpcAttachment {

    private String transitGatewayAttachmentId;
    private String transitGatewayId;
    private String vpcId;
    private String vpcOwnerId;
    private String state = "available";
    private List<String> subnetIds = new ArrayList<>();
    private Instant creationTime;
    private String region;
    private TransitGatewayVpcAttachmentOptions options = new TransitGatewayVpcAttachmentOptions();
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

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public TransitGatewayVpcAttachmentOptions getOptions() { return options; }
    public void setOptions(TransitGatewayVpcAttachmentOptions options) { this.options = options; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
