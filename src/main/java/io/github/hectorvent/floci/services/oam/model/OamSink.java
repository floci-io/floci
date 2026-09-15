package io.github.hectorvent.floci.services.oam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class OamSink {
    private String arn;
    private String id;
    private String name;
    private String region;
    private String ownerAccountId;
    private String policy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public OamSink() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }
}
