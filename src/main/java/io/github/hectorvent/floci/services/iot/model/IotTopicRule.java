package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.hectorvent.floci.services.iot.rules.RuleSql;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@RegisterForReflection
public class IotTopicRule {
    private String ruleName;
    private String ruleArn;
    private String sql;

    /**
     * The parsed form of {@link #sql}, so a rule is parsed once instead of once per message.
     * The SQL string stays the source of truth, so this is never persisted and is rebuilt on
     * the first publish after a restart.
     */
    @JsonIgnore
    private transient volatile RuleSql.Compilation compiledSql;
    private String description;
    private boolean ruleDisabled;
    private String actionsJson = "[]";
    private String awsIotSqlVersion;
    private String errorActionJson;
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
        this.compiledSql = null;
    }

    public RuleSql.Compilation getCompiledSql() {
        return compiledSql;
    }

    public void setCompiledSql(RuleSql.Compilation compiledSql) {
        this.compiledSql = compiledSql;
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

    public String getAwsIotSqlVersion() {
        return awsIotSqlVersion;
    }

    public void setAwsIotSqlVersion(String awsIotSqlVersion) {
        this.awsIotSqlVersion = awsIotSqlVersion;
    }

    public String getErrorActionJson() {
        return errorActionJson;
    }

    public void setErrorActionJson(String errorActionJson) {
        this.errorActionJson = errorActionJson;
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
