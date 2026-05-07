package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupVault {

    @JsonProperty("BackupVaultName")
    private String backupVaultName;

    @JsonProperty("BackupVaultArn")
    private String backupVaultArn;

    @JsonProperty("EncryptionKeyArn")
    private String encryptionKeyArn;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CreatorRequestId")
    private String creatorRequestId;

    @JsonProperty("NumberOfRecoveryPoints")
    private long numberOfRecoveryPoints;

    @JsonProperty("Tags")
    private Map<String, String> tags = new HashMap<>();

    public BackupVault() {}

    public String getBackupVaultName() { return backupVaultName; }
    public void setBackupVaultName(String backupVaultName) { this.backupVaultName = backupVaultName; }

    public String getBackupVaultArn() { return backupVaultArn; }
    public void setBackupVaultArn(String backupVaultArn) { this.backupVaultArn = backupVaultArn; }

    public String getEncryptionKeyArn() { return encryptionKeyArn; }
    public void setEncryptionKeyArn(String encryptionKeyArn) { this.encryptionKeyArn = encryptionKeyArn; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public String getCreatorRequestId() { return creatorRequestId; }
    public void setCreatorRequestId(String creatorRequestId) { this.creatorRequestId = creatorRequestId; }

    public long getNumberOfRecoveryPoints() { return numberOfRecoveryPoints; }
    public void setNumberOfRecoveryPoints(long numberOfRecoveryPoints) { this.numberOfRecoveryPoints = numberOfRecoveryPoints; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
