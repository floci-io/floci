package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupRule {

    @JsonProperty("RuleName")
    private String ruleName;

    @JsonProperty("TargetBackupVaultName")
    private String targetBackupVaultName;

    @JsonProperty("ScheduleExpression")
    private String scheduleExpression;

    @JsonProperty("ScheduleExpressionTimezone")
    private String scheduleExpressionTimezone;

    @JsonProperty("StartWindowMinutes")
    private Long startWindowMinutes;

    @JsonProperty("CompletionWindowMinutes")
    private Long completionWindowMinutes;

    @JsonProperty("Lifecycle")
    private Lifecycle lifecycle;

    @JsonProperty("RecoveryPointTags")
    private Map<String, String> recoveryPointTags = new HashMap<>();

    @JsonProperty("RuleId")
    private String ruleId;

    @JsonProperty("CopyActions")
    private List<CopyAction> copyActions = new ArrayList<>();

    @JsonProperty("EnableContinuousBackup")
    private Boolean enableContinuousBackup;

    public BackupRule() {}

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getTargetBackupVaultName() { return targetBackupVaultName; }
    public void setTargetBackupVaultName(String targetBackupVaultName) { this.targetBackupVaultName = targetBackupVaultName; }

    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }

    public String getScheduleExpressionTimezone() { return scheduleExpressionTimezone; }
    public void setScheduleExpressionTimezone(String scheduleExpressionTimezone) { this.scheduleExpressionTimezone = scheduleExpressionTimezone; }

    public Long getStartWindowMinutes() { return startWindowMinutes; }
    public void setStartWindowMinutes(Long startWindowMinutes) { this.startWindowMinutes = startWindowMinutes; }

    public Long getCompletionWindowMinutes() { return completionWindowMinutes; }
    public void setCompletionWindowMinutes(Long completionWindowMinutes) { this.completionWindowMinutes = completionWindowMinutes; }

    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }

    public Map<String, String> getRecoveryPointTags() { return recoveryPointTags; }
    public void setRecoveryPointTags(Map<String, String> recoveryPointTags) { this.recoveryPointTags = recoveryPointTags != null ? recoveryPointTags : new HashMap<>(); }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public List<CopyAction> getCopyActions() { return copyActions; }
    public void setCopyActions(List<CopyAction> copyActions) { this.copyActions = copyActions != null ? copyActions : new ArrayList<>(); }

    public Boolean getEnableContinuousBackup() { return enableContinuousBackup; }
    public void setEnableContinuousBackup(Boolean enableContinuousBackup) { this.enableContinuousBackup = enableContinuousBackup; }
}
