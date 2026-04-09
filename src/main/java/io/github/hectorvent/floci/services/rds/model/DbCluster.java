package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class DbCluster {

    private String dbClusterIdentifier;
    private DatabaseEngine engine;
    private String engineVersion;
    private String masterUsername;
    private String masterPassword;
    private String databaseName;
    private DbInstanceStatus status;
    private DbEndpoint endpoint;
    private DbEndpoint readerEndpoint;
    private boolean iamDatabaseAuthenticationEnabled;
    private List<String> dbClusterMembers = new ArrayList<>();
    private String parameterGroupName;
    private String dbClusterResourceId;
    private String dbClusterArn;
    private Instant createdAt;
    private int proxyPort;

    // Transient — not persisted
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public DbCluster() {}

    public DbCluster(String dbClusterIdentifier, DatabaseEngine engine, String engineVersion,
                     String masterUsername, String masterPassword, String databaseName,
                     DbInstanceStatus status, DbEndpoint endpoint, DbEndpoint readerEndpoint,
                     boolean iamDatabaseAuthenticationEnabled, List<String> dbClusterMembers,
                     String parameterGroupName, Instant createdAt, int proxyPort) {
        this.dbClusterIdentifier = dbClusterIdentifier;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.databaseName = databaseName;
        this.status = status;
        this.endpoint = endpoint;
        this.readerEndpoint = readerEndpoint;
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
        this.dbClusterMembers = dbClusterMembers != null ? new ArrayList<>(dbClusterMembers) : new ArrayList<>();
        this.parameterGroupName = parameterGroupName;
        this.createdAt = createdAt;
        this.proxyPort = proxyPort;
    }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public DatabaseEngine getEngine() { return engine; }
    public void setEngine(DatabaseEngine engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public DbInstanceStatus getStatus() { return status; }
    public void setStatus(DbInstanceStatus status) { this.status = status; }

    public DbEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(DbEndpoint endpoint) { this.endpoint = endpoint; }

    public DbEndpoint getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(DbEndpoint readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public List<String> getDbClusterMembers() { return dbClusterMembers; }
    public void setDbClusterMembers(List<String> dbClusterMembers) { this.dbClusterMembers = dbClusterMembers; }

    public String getParameterGroupName() { return parameterGroupName; }
    public void setParameterGroupName(String parameterGroupName) { this.parameterGroupName = parameterGroupName; }

    public String getDbClusterResourceId() { return dbClusterResourceId; }
    public void setDbClusterResourceId(String dbClusterResourceId) { this.dbClusterResourceId = dbClusterResourceId; }

    public String getDbClusterArn() { return dbClusterArn; }
    public void setDbClusterArn(String dbClusterArn) { this.dbClusterArn = dbClusterArn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
