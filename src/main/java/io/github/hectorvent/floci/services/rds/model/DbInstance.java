package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class DbInstance {

    private String dbInstanceIdentifier;
    private DatabaseEngine engine;
    private String engineVersion;
    private String masterUsername;
    private String masterPassword;
    private String dbName;
    private String dbInstanceClass;
    private int allocatedStorage;
    private DbInstanceStatus status;
    private DbEndpoint endpoint;
    private boolean iamDatabaseAuthenticationEnabled;
    private String parameterGroupName;
    private String dbClusterIdentifier;
    private String dbiResourceId;
    private String dbInstanceArn;
    private Instant createdAt;
    private int proxyPort;

    // Transient — not persisted; restored on startup by re-launching containers
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public DbInstance() {}

    public DbInstance(String dbInstanceIdentifier, DatabaseEngine engine, String engineVersion,
                      String masterUsername, String masterPassword, String dbName,
                      String dbInstanceClass, int allocatedStorage, DbInstanceStatus status,
                      DbEndpoint endpoint, boolean iamDatabaseAuthenticationEnabled,
                      String parameterGroupName, String dbClusterIdentifier,
                      Instant createdAt, int proxyPort) {
        this.dbInstanceIdentifier = dbInstanceIdentifier;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.dbInstanceClass = dbInstanceClass;
        this.allocatedStorage = allocatedStorage;
        this.status = status;
        this.endpoint = endpoint;
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
        this.parameterGroupName = parameterGroupName;
        this.dbClusterIdentifier = dbClusterIdentifier;
        this.createdAt = createdAt;
        this.proxyPort = proxyPort;
    }

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) { this.dbInstanceIdentifier = dbInstanceIdentifier; }

    public DatabaseEngine getEngine() { return engine; }
    public void setEngine(DatabaseEngine engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getDbInstanceClass() { return dbInstanceClass; }
    public void setDbInstanceClass(String dbInstanceClass) { this.dbInstanceClass = dbInstanceClass; }

    public int getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(int allocatedStorage) { this.allocatedStorage = allocatedStorage; }

    public DbInstanceStatus getStatus() { return status; }
    public void setStatus(DbInstanceStatus status) { this.status = status; }

    public DbEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(DbEndpoint endpoint) { this.endpoint = endpoint; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public String getParameterGroupName() { return parameterGroupName; }
    public void setParameterGroupName(String parameterGroupName) { this.parameterGroupName = parameterGroupName; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getDbiResourceId() { return dbiResourceId; }
    public void setDbiResourceId(String dbiResourceId) { this.dbiResourceId = dbiResourceId; }

    public String getDbInstanceArn() { return dbInstanceArn; }
    public void setDbInstanceArn(String dbInstanceArn) { this.dbInstanceArn = dbInstanceArn; }

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
