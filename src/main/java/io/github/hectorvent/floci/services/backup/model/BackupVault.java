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

    // Vault Lock. These four ARE part of the DescribeBackupVault response in AWS, unlike
    // the access policy and the notification configuration, which have their own Get
    // operations and are stored separately.
    //
    // `locked` is a primitive, so it serialises as `false` on an unlocked vault rather
    // than being dropped by @JsonInclude(NON_NULL). That matches AWS, which always
    // reports Locked, and it matters: a client that has to distinguish "not locked" from
    // "this emulator does not model locking" can only do so if the member is present.
    // The other three stay boxed, because AWS omits them until a lock exists.
    @JsonProperty("Locked")
    private boolean locked;

    @JsonProperty("LockDate")
    private Long lockDate;

    @JsonProperty("MinRetentionDays")
    private Long minRetentionDays;

    @JsonProperty("MaxRetentionDays")
    private Long maxRetentionDays;

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

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public Long getLockDate() { return lockDate; }
    public void setLockDate(Long lockDate) { this.lockDate = lockDate; }

    public Long getMinRetentionDays() { return minRetentionDays; }
    public void setMinRetentionDays(Long minRetentionDays) { this.minRetentionDays = minRetentionDays; }

    public Long getMaxRetentionDays() { return maxRetentionDays; }
    public void setMaxRetentionDays(Long maxRetentionDays) { this.maxRetentionDays = maxRetentionDays; }
}
