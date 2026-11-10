package io.floci.aws.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Topic {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("TopicArn")
    private String topicArn;

    @JsonProperty("Attributes")
    private Map<String, String> attributes = new HashMap<>();

    @JsonProperty("Tags")
    private Map<String, String> tags = new HashMap<>();

    @JsonProperty("CreatedAt")
    private Instant createdAt;

    public Topic() {}

    public Topic(String name, String topicArn) {
        this.name = name;
        this.topicArn = topicArn;
        this.createdAt = Instant.now();
        this.attributes.put("TopicArn", topicArn);
        this.attributes.put("DisplayName", "");
        this.attributes.put("Policy", "");
        this.attributes.put("DeliveryPolicy", "");
        this.attributes.put("EffectiveDeliveryPolicy", "");
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopicArn() { return topicArn; }
    public void setTopicArn(String topicArn) { this.topicArn = topicArn; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
