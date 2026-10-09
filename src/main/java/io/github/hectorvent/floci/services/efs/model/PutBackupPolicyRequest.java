package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

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
