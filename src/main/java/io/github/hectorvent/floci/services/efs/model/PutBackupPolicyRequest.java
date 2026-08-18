package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutBackupPolicyRequest {
    private String fileSystemId;
    private BackupPolicy backupPolicy;
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
    public BackupPolicy getBackupPolicy() { return backupPolicy; }
    public void setBackupPolicy(BackupPolicy backupPolicy) { this.backupPolicy = backupPolicy; }
}
