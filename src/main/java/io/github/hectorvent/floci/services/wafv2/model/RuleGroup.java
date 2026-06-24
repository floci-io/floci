package io.github.hectorvent.floci.services.wafv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * WAF v2 rule group. {@code rules}, {@code visibilityConfig} and
 * {@code customResponseBodies} are stored as raw JSON strings. {@code capacity} is the
 * caller-declared WCU budget for the group.
 */
@RegisterForReflection
public class RuleGroup {

    private String id;
    private String name;
    private String arn;
    private String scope;
    private String description;
    private long capacity;
    private String rules;
    private String visibilityConfig;
    private String customResponseBodies;
    private String labelNamespace;
    private String lockToken;
    private String region;
    private Map<String, String> tags = new HashMap<>();

    public RuleGroup() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }

    public String getVisibilityConfig() { return visibilityConfig; }
    public void setVisibilityConfig(String visibilityConfig) { this.visibilityConfig = visibilityConfig; }

    public String getCustomResponseBodies() { return customResponseBodies; }
    public void setCustomResponseBodies(String customResponseBodies) { this.customResponseBodies = customResponseBodies; }

    public String getLabelNamespace() { return labelNamespace; }
    public void setLabelNamespace(String labelNamespace) { this.labelNamespace = labelNamespace; }

    public String getLockToken() { return lockToken; }
    public void setLockToken(String lockToken) { this.lockToken = lockToken; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
