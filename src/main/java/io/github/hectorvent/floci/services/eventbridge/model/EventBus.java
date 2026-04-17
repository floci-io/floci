package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class EventBus {

    private String name;
    private String arn;
    private String description;
    private Map<String, String> tags = new HashMap<>();
    private Instant createdTime;
    private String policy;

    public EventBus() {}

    public EventBus(String name, String arn, String description, Instant createdTime) {
        this.name = name;
        this.arn = arn;
        this.description = description;
        this.createdTime = createdTime;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
