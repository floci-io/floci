package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupJob {

    @JsonProperty("BackupJobId")
    private String backupJobId;

    @JsonProperty("BackupVaultName")
    private String backupVaultName;

    @JsonProperty("BackupVaultArn")
    private String backupVaultArn;

    @JsonProperty("RecoveryPointArn")
    private String recoveryPointArn;

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("State")
    private String state;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    @JsonProperty("ExpectedCompletionDate")
    private Long expectedCompletionDate;

    @JsonProperty("StartBy")
    private Long startBy;

    @JsonProperty("BytesTransferred")
    private Long bytesTransferred;

    @JsonProperty("BackupSizeInBytes")
    private Long backupSizeInBytes;

    @JsonProperty("PercentDone")
    private String percentDone;

    @JsonProperty("AccountId")
    private String accountId;

    public BackupJob() {}

    public String getBackupJobId() { return backupJobId; }
    public void setBackupJobId(String backupJobId) { this.backupJobId = backupJobId; }

    public String getBackupVaultName() { return backupVaultName; }
    public void setBackupVaultName(String backupVaultName) { this.backupVaultName = backupVaultName; }

    public String getBackupVaultArn() { return backupVaultArn; }
    public void setBackupVaultArn(String backupVaultArn) { this.backupVaultArn = backupVaultArn; }

    public String getRecoveryPointArn() { return recoveryPointArn; }
    public void setRecoveryPointArn(String recoveryPointArn) { this.recoveryPointArn = recoveryPointArn; }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }

    public Long getExpectedCompletionDate() { return expectedCompletionDate; }
    public void setExpectedCompletionDate(Long expectedCompletionDate) { this.expectedCompletionDate = expectedCompletionDate; }

    public Long getStartBy() { return startBy; }
    public void setStartBy(Long startBy) { this.startBy = startBy; }

    public Long getBytesTransferred() { return bytesTransferred; }
    public void setBytesTransferred(Long bytesTransferred) { this.bytesTransferred = bytesTransferred; }

    public Long getBackupSizeInBytes() { return backupSizeInBytes; }
    public void setBackupSizeInBytes(Long backupSizeInBytes) { this.backupSizeInBytes = backupSizeInBytes; }

    public String getPercentDone() { return percentDone; }
    public void setPercentDone(String percentDone) { this.percentDone = percentDone; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
