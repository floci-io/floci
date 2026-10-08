package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutBackupPolicyResponse {
    private BackupPolicy backupPolicy;
    public BackupPolicy getBackupPolicy() { return backupPolicy; }
    public void setBackupPolicy(BackupPolicy backupPolicy) { this.backupPolicy = backupPolicy; }
}
