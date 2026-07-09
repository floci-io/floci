package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@RegisterForReflection
public class IotTopicRule {
    private String ruleName;
    private String ruleArn;
    private String sql;
    private String description;
    private boolean ruleDisabled;
    private String actionsJson = "[]";
    private Instant createdAt;
    private Map<String, String> tags = new TreeMap<>();

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleArn() {
        return ruleArn;
    }

    public void setRuleArn(String ruleArn) {
        this.ruleArn = ruleArn;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isRuleDisabled() {
        return ruleDisabled;
    }

    public void setRuleDisabled(boolean ruleDisabled) {
        this.ruleDisabled = ruleDisabled;
    }

    public String getActionsJson() {
        return actionsJson;
    }

    public void setActionsJson(String actionsJson) {
        this.actionsJson = actionsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags == null ? Map.of() : tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new TreeMap<>() : new TreeMap<>(tags);
    }
}
