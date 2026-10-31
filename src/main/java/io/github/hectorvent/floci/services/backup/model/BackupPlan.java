package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupPlan {

    @JsonProperty("BackupPlanId")
    private String backupPlanId;

    @JsonProperty("BackupPlanArn")
    private String backupPlanArn;

    @JsonProperty("BackupPlanName")
    private String backupPlanName;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("DeletionDate")
    private Long deletionDate;

    @JsonProperty("LastExecutionDate")
    private Long lastExecutionDate;

    @JsonProperty("VersionId")
    private String versionId;

    @JsonProperty("Rules")
    private List<BackupRule> rules = new ArrayList<>();

    @JsonProperty("Tags")
    private Map<String, String> tags = new HashMap<>();

    public BackupPlan() {}

    public String getBackupPlanId() { return backupPlanId; }
    public void setBackupPlanId(String backupPlanId) { this.backupPlanId = backupPlanId; }

    public String getBackupPlanArn() { return backupPlanArn; }
    public void setBackupPlanArn(String backupPlanArn) { this.backupPlanArn = backupPlanArn; }

    public String getBackupPlanName() { return backupPlanName; }
    public void setBackupPlanName(String backupPlanName) { this.backupPlanName = backupPlanName; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getDeletionDate() { return deletionDate; }
    public void setDeletionDate(Long deletionDate) { this.deletionDate = deletionDate; }

    public Long getLastExecutionDate() { return lastExecutionDate; }
    public void setLastExecutionDate(Long lastExecutionDate) { this.lastExecutionDate = lastExecutionDate; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>(); }

    public List<BackupRule> getRules() { return rules; }
    public void setRules(List<BackupRule> rules) { this.rules = rules != null ? rules : new ArrayList<>(); }
}
