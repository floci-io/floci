package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class FileSystemProtectionDescription {

    private ReplicationOverwriteProtection replicationOverwriteProtection;

    public ReplicationOverwriteProtection getReplicationOverwriteProtection() {
        return replicationOverwriteProtection;
    }

    public void setReplicationOverwriteProtection(
            ReplicationOverwriteProtection replicationOverwriteProtection) {
        this.replicationOverwriteProtection = replicationOverwriteProtection;
    }
}