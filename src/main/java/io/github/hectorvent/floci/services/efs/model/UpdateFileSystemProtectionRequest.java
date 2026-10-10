package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class UpdateFileSystemProtectionRequest {

    private ReplicationOverwriteProtection replicationOverwriteProtection;

    public ReplicationOverwriteProtection getReplicationOverwriteProtection() {
        return replicationOverwriteProtection;
    }

    public void setReplicationOverwriteProtection(
            ReplicationOverwriteProtection replicationOverwriteProtection) {
        this.replicationOverwriteProtection = replicationOverwriteProtection;
    }
}
