package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGateway {

    private String transitGatewayId;
    private String transitGatewayArn;
    private String state;
    private String ownerId;
    private String description;
    private String creationTime;
    private String region;
    private TransitGatewayOptions options = new TransitGatewayOptions();
    private List<Tag> tags = new ArrayList<>();

    public TransitGateway() {}

    public String getTransitGatewayId() { return transitGatewayId; }
    public void setTransitGatewayId(String transitGatewayId) { this.transitGatewayId = transitGatewayId; }

    public String getTransitGatewayArn() { return transitGatewayArn; }
    public void setTransitGatewayArn(String transitGatewayArn) { this.transitGatewayArn = transitGatewayArn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public TransitGatewayOptions getOptions() { return options; }
    public void setOptions(TransitGatewayOptions options) { this.options = options; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
