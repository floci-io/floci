package io.github.hectorvent.floci.services.efs.model;

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