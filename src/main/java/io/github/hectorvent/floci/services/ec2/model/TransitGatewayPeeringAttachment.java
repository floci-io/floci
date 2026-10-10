package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A peering attachment between two transit gateways. One attachment id serves both sides, so a
 * cross-region peering is visible from the requester's region and the accepter's alike.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayPeeringAttachment {

    private String transitGatewayAttachmentId;
    private TgwInfo requesterTgwInfo = new TgwInfo();
    private TgwInfo accepterTgwInfo = new TgwInfo();
    private String dynamicRouting;
    private String state;
    private String creationTime;
    /** Filled from the tag store on read; tags are not persisted on the record itself. */
    @JsonIgnore
    private List<Tag> tags = new ArrayList<>();

    public TransitGatewayPeeringAttachment() {}

    public String getTransitGatewayAttachmentId() { return transitGatewayAttachmentId; }
    public void setTransitGatewayAttachmentId(String transitGatewayAttachmentId) {
        this.transitGatewayAttachmentId = transitGatewayAttachmentId;
    }

    public TgwInfo getRequesterTgwInfo() { return requesterTgwInfo; }
    public void setRequesterTgwInfo(TgwInfo requesterTgwInfo) { this.requesterTgwInfo = requesterTgwInfo; }

    public TgwInfo getAccepterTgwInfo() { return accepterTgwInfo; }
    public void setAccepterTgwInfo(TgwInfo accepterTgwInfo) { this.accepterTgwInfo = accepterTgwInfo; }

    public String getDynamicRouting() { return dynamicRouting; }
    public void setDynamicRouting(String dynamicRouting) { this.dynamicRouting = dynamicRouting; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    /** One side of the peering: the {@code PeeringTgwInfo} shape. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TgwInfo {
        private String transitGatewayId;
        private String ownerId;
        private String region;

        public TgwInfo() {}

        public TgwInfo(String transitGatewayId, String ownerId, String region) {
            this.transitGatewayId = transitGatewayId;
            this.ownerId = ownerId;
            this.region = region;
        }

        public String getTransitGatewayId() { return transitGatewayId; }
        public void setTransitGatewayId(String transitGatewayId) { this.transitGatewayId = transitGatewayId; }

        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }
}
