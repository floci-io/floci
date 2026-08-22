package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Snapshot {
    private String snapshotIdentifier;
    private String clusterIdentifier;
    private String status;
    private int port;
    private String masterUsername;

    public Snapshot() {}

    public Snapshot(String snapshotIdentifier, String clusterIdentifier, String status, int port, String masterUsername) {
        this.snapshotIdentifier = snapshotIdentifier;
        this.clusterIdentifier = clusterIdentifier;
        this.status = status;
        this.port = port;
        this.masterUsername = masterUsername;
    }

    public String getSnapshotIdentifier() {
        return snapshotIdentifier;
    }

    public void setSnapshotIdentifier(String snapshotIdentifier) {
        this.snapshotIdentifier = snapshotIdentifier;
    }

    public String getClusterIdentifier() {
        return clusterIdentifier;
    }

    public void setClusterIdentifier(String clusterIdentifier) {
        this.clusterIdentifier = clusterIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMasterUsername() {
        return masterUsername;
    }

    public void setMasterUsername(String masterUsername) {
        this.masterUsername = masterUsername;
    }
}
