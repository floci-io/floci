package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class PutBackupPolicyResponse {
    private BackupPolicy backupPolicy;
    public BackupPolicy getBackupPolicy() { return backupPolicy; }
    public void setBackupPolicy(BackupPolicy backupPolicy) { this.backupPolicy = backupPolicy; }
}
