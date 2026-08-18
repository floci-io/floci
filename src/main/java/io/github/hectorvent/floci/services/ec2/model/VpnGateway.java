package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The AWS side of a Site-to-Site VPN connection: the gateway a VPC attaches to via
 * {@code AttachVpnGateway}.
 *
 * <p>{@code state} describes the gateway itself (pending/available/deleting/deleted) and never
 * changes on attach or detach — only the {@link VpcAttachment#getState()} of each entry in
 * {@code vpcAttachments} reflects attaching/attached/detaching/detached, matching real AWS.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpnGateway {

    private String vpnGatewayId;
    private String state = "available";
    private String type;
    private String availabilityZone;
    private long amazonSideAsn = 64512L;
    private String ownerId;
    private String region;
    private List<VpcAttachment> vpcAttachments = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public VpnGateway() {}

    public String getVpnGatewayId() { return vpnGatewayId; }
    public void setVpnGatewayId(String vpnGatewayId) { this.vpnGatewayId = vpnGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public long getAmazonSideAsn() { return amazonSideAsn; }
    public void setAmazonSideAsn(long amazonSideAsn) { this.amazonSideAsn = amazonSideAsn; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<VpcAttachment> getVpcAttachments() { return vpcAttachments; }
    public void setVpcAttachments(List<VpcAttachment> vpcAttachments) { this.vpcAttachments = vpcAttachments; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
