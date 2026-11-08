package io.floci.aws.services.elasticache.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A manual snapshot of a cluster-mode-disabled replication group: the record CreateSnapshot and
 * DescribeSnapshots answer with. The RDB archive it restores from is stored apart from it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ElastiCacheSnapshot {

    private String snapshotName;
    private String replicationGroupId;
    private String replicationGroupDescription;
    private String cacheClusterId;
    private String snapshotStatus;
    private String snapshotSource;
    private String engine;
    private String engineVersion;
    private String cacheNodeType;
    private int numCacheClusters;
    private String arn;
    private Instant createdAt;
    private long sizeBytes;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ElastiCacheSnapshot() {
    }

    public String getSnapshotName() { return snapshotName; }
    public void setSnapshotName(String snapshotName) { this.snapshotName = snapshotName; }

    public String getReplicationGroupId() { return replicationGroupId; }
    public void setReplicationGroupId(String replicationGroupId) { this.replicationGroupId = replicationGroupId; }

    public String getReplicationGroupDescription() { return replicationGroupDescription; }
    public void setReplicationGroupDescription(String description) { this.replicationGroupDescription = description; }

    public String getCacheClusterId() { return cacheClusterId; }
    public void setCacheClusterId(String cacheClusterId) { this.cacheClusterId = cacheClusterId; }

    public String getSnapshotStatus() { return snapshotStatus; }
    public void setSnapshotStatus(String snapshotStatus) { this.snapshotStatus = snapshotStatus; }

    public String getSnapshotSource() { return snapshotSource; }
    public void setSnapshotSource(String snapshotSource) { this.snapshotSource = snapshotSource; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getCacheNodeType() { return cacheNodeType; }
    public void setCacheNodeType(String cacheNodeType) { this.cacheNodeType = cacheNodeType; }

    public int getNumCacheClusters() { return numCacheClusters; }
    public void setNumCacheClusters(int numCacheClusters) { this.numCacheClusters = numCacheClusters; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new LinkedHashMap<>(); }
}
