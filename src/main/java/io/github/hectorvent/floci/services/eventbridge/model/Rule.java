package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {

    private String name;
    private String arn;
    private String accountId;
    private String eventBusName;
    private String eventPattern;
    private String scheduleExpression;
    private RuleState state = RuleState.ENABLED;
    private String description;
    private String roleArn;
    private Map<String, String> tags = new HashMap<>();
    private Instant createdAt;

    public Rule() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getEventBusName() { return eventBusName; }
    public void setEventBusName(String eventBusName) { this.eventBusName = eventBusName; }

    public String getEventPattern() { return eventPattern; }
    public void setEventPattern(String eventPattern) { this.eventPattern = eventPattern; }

    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }

    public RuleState getState() { return state; }
    public void setState(RuleState state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @JsonIgnore
    public String getRegion() {
        return AwsArnUtils.regionOrDefault(arn, null);
    }
}
