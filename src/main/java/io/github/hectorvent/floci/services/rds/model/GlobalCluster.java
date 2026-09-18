package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An Aurora global database: an account-wide record (its ARN carries no Region) holding one
 * primary cluster and any number of secondaries, each in its own Region.
 */
@RegisterForReflection
public class GlobalCluster {

    private String globalClusterIdentifier;
    private String globalClusterResourceId;
    private String globalClusterArn;
    private String status;
    private String engine;
    private String engineVersion;
    private String databaseName;
    private boolean storageEncrypted;
    private boolean deletionProtection;
    private List<GlobalClusterMember> members = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Instant createdAt;

    public GlobalCluster() {}

    public String getGlobalClusterIdentifier() { return globalClusterIdentifier; }
    public void setGlobalClusterIdentifier(String globalClusterIdentifier) { this.globalClusterIdentifier = globalClusterIdentifier; }

    public String getGlobalClusterResourceId() { return globalClusterResourceId; }
    public void setGlobalClusterResourceId(String globalClusterResourceId) { this.globalClusterResourceId = globalClusterResourceId; }

    public String getGlobalClusterArn() { return globalClusterArn; }
    public void setGlobalClusterArn(String globalClusterArn) { this.globalClusterArn = globalClusterArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean storageEncrypted) { this.storageEncrypted = storageEncrypted; }

    public boolean isDeletionProtection() { return deletionProtection; }
    public void setDeletionProtection(boolean deletionProtection) { this.deletionProtection = deletionProtection; }

    public List<GlobalClusterMember> getMembers() { return members; }
    public void setMembers(List<GlobalClusterMember> members) {
        this.members = members != null ? new ArrayList<>(members) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    // Helpers, not bean properties: the persisted form must carry only settable properties.

    public Optional<GlobalClusterMember> findPrimary() {
        return members.stream().filter(GlobalClusterMember::isWriter).findFirst();
    }

    public Optional<GlobalClusterMember> findMember(String dbClusterArn) {
        return members.stream()
                .filter(member -> member.getDbClusterArn().equalsIgnoreCase(dbClusterArn))
                .findFirst();
    }
}
