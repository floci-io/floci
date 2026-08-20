package io.github.hectorvent.floci.services.docdb.model;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
@RegisterForReflection
public class DocDbInstance {

    private String dbInstanceIdentifier;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String dbClusterIdentifier;
    private String dbInstanceClass;
    private String engineVersion;
    private String status;
    private String endpoint;
    private int port;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbInstanceArn;
    private String dbiResourceId;
    private Instant createdAt;

    public DocDbInstance(){}

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) { this.dbInstanceIdentifier = dbInstanceIdentifier; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getDbInstanceClass() { return dbInstanceClass; }
    public void setDbInstanceClass(String dbInstanceClass) { this.dbInstanceClass = dbInstanceClass; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public String getDbInstanceArn() { return dbInstanceArn; }
    public void setDbInstanceArn(String dbInstanceArn) { this.dbInstanceArn = dbInstanceArn; }

    public String getDbiResourceId() { return dbiResourceId; }
    public void setDbiResourceId(String dbiResourceId) { this.dbiResourceId = dbiResourceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }



    public Map<String, String> getTags() { return tags; }

    /** Normalizes null: a record persisted before tags were stored deserializes without them. */
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
