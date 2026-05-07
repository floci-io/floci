package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecoveryPoint {

    @JsonProperty("RecoveryPointArn")
    private String recoveryPointArn;

    @JsonProperty("BackupVaultName")
    private String backupVaultName;

    @JsonProperty("BackupVaultArn")
    private String backupVaultArn;

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    @JsonProperty("BackupSizeInBytes")
    private Long backupSizeInBytes;

    @JsonProperty("Lifecycle")
    private Lifecycle lifecycle;

    @JsonProperty("EncryptionKeyArn")
    private String encryptionKeyArn;

    @JsonProperty("IsEncrypted")
    private boolean isEncrypted;

    @JsonProperty("StorageClass")
    private String storageClass;

    @JsonProperty("LastRestoreTime")
    private Long lastRestoreTime;

    public RecoveryPoint() {}

    public String getRecoveryPointArn() { return recoveryPointArn; }
    public void setRecoveryPointArn(String recoveryPointArn) { this.recoveryPointArn = recoveryPointArn; }

    public String getBackupVaultName() { return backupVaultName; }
    public void setBackupVaultName(String backupVaultName) { this.backupVaultName = backupVaultName; }

    public String getBackupVaultArn() { return backupVaultArn; }
    public void setBackupVaultArn(String backupVaultArn) { this.backupVaultArn = backupVaultArn; }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }

    public Long getBackupSizeInBytes() { return backupSizeInBytes; }
    public void setBackupSizeInBytes(Long backupSizeInBytes) { this.backupSizeInBytes = backupSizeInBytes; }

    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }

    public String getEncryptionKeyArn() { return encryptionKeyArn; }
    public void setEncryptionKeyArn(String encryptionKeyArn) { this.encryptionKeyArn = encryptionKeyArn; }

    public boolean isEncrypted() { return isEncrypted; }
    public void setEncrypted(boolean encrypted) { isEncrypted = encrypted; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public Long getLastRestoreTime() { return lastRestoreTime; }
    public void setLastRestoreTime(Long lastRestoreTime) { this.lastRestoreTime = lastRestoreTime; }
}
