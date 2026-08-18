package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutBackupPolicyResponse {
    private BackupPolicy backupPolicy;
    public BackupPolicy getBackupPolicy() { return backupPolicy; }
    public void setBackupPolicy(BackupPolicy backupPolicy) { this.backupPolicy = backupPolicy; }
}
