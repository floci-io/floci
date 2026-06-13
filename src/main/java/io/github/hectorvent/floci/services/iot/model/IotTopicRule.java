package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;

public class IotTopicRule {
    private String ruleName;
    private String ruleArn;
    private String sql;
    private String description;
    private boolean ruleDisabled;
    private String actionsJson = "[]";
    private Instant createdAt;

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
}
