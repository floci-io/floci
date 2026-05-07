package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupSelection {

    @JsonProperty("SelectionId")
    private String selectionId;

    @JsonProperty("SelectionName")
    private String selectionName;

    @JsonProperty("BackupPlanId")
    private String backupPlanId;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("Resources")
    private List<String> resources = new ArrayList<>();

    @JsonProperty("NotResources")
    private List<String> notResources = new ArrayList<>();

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CreatorRequestId")
    private String creatorRequestId;

    public BackupSelection() {}

    public String getSelectionId() { return selectionId; }
    public void setSelectionId(String selectionId) { this.selectionId = selectionId; }

    public String getSelectionName() { return selectionName; }
    public void setSelectionName(String selectionName) { this.selectionName = selectionName; }

    public String getBackupPlanId() { return backupPlanId; }
    public void setBackupPlanId(String backupPlanId) { this.backupPlanId = backupPlanId; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public List<String> getResources() { return resources; }
    public void setResources(List<String> resources) { this.resources = resources != null ? resources : new ArrayList<>(); }

    public List<String> getNotResources() { return notResources; }
    public void setNotResources(List<String> notResources) { this.notResources = notResources != null ? notResources : new ArrayList<>(); }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public String getCreatorRequestId() { return creatorRequestId; }
    public void setCreatorRequestId(String creatorRequestId) { this.creatorRequestId = creatorRequestId; }
}
