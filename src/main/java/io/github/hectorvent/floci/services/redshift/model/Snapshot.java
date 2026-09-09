package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class Snapshot {
    private String snapshotIdentifier;
    private String clusterIdentifier;
    private String status;
    private int port;
    private String masterUsername;
    private String masterPassword;
    private String sqlDump;

    public Snapshot() {}

    public Snapshot(String snapshotIdentifier, String clusterIdentifier, String status, int port, String masterUsername) {
        this.snapshotIdentifier = snapshotIdentifier;
        this.clusterIdentifier = clusterIdentifier;
        this.status = status;
        this.port = port;
        this.masterUsername = masterUsername;
    }

    public Snapshot(String snapshotIdentifier, String clusterIdentifier, String status, int port, String masterUsername, String sqlDump) {
        this.snapshotIdentifier = snapshotIdentifier;
        this.clusterIdentifier = clusterIdentifier;
        this.status = status;
        this.port = port;
        this.masterUsername = masterUsername;
        this.sqlDump = sqlDump;
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

    public String getMasterPassword() {
        return masterPassword;
    }

    public void setMasterPassword(String masterPassword) {
        this.masterPassword = masterPassword;
    }

    public String getSqlDump() {
        return sqlDump;
    }

    public void setSqlDump(String sqlDump) {
        this.sqlDump = sqlDump;
    }

    private Map<String, String> tags = new LinkedHashMap<>();

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
