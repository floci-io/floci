package io.github.hectorvent.floci.services.docdb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class DocDbCluster {
    private String masterUsername;

    private String dbClusterIdentifier;
    private String status;
    private String engineVersion;
    private String endpoint;
    private int port;
    private String readerEndpoint;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbClusterArn;
    private String dbClusterResourceId;
    private List<String> dbClusterMembers = new ArrayList<>();
    private Instant createdAt;

    // Docker / proxy runtime fields — persisted so cleanup works across restarts
    private String containerId;
    private String containerHost;
    private int containerPort;

    public DocDbCluster (){}

    public String getMasterUsername(){return masterUsername;}
    public void setMasterUsername(String masterUsername){this.masterUsername = masterUsername;}

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(String readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public String getDbClusterArn() { return dbClusterArn; }
    public void setDbClusterArn(String dbClusterArn) { this.dbClusterArn = dbClusterArn; }

    public String getDbClusterResourceId() { return dbClusterResourceId; }
    public void setDbClusterResourceId(String dbClusterResourceId) { this.dbClusterResourceId = dbClusterResourceId; }

    public List<String> getDbClusterMembers() { return dbClusterMembers; }
    public void setDbClusterMembers(List<String> dbClusterMembers) {
        this.dbClusterMembers = dbClusterMembers != null ? new ArrayList<>(dbClusterMembers) : new ArrayList<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }


}