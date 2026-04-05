package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternetGateway {

    private String internetGatewayId;
    private String ownerId;
    private String region;
    private List<InternetGatewayAttachment> attachments = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public InternetGateway() {}

    public String getInternetGatewayId() { return internetGatewayId; }
    public void setInternetGatewayId(String internetGatewayId) { this.internetGatewayId = internetGatewayId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<InternetGatewayAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<InternetGatewayAttachment> attachments) { this.attachments = attachments; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
