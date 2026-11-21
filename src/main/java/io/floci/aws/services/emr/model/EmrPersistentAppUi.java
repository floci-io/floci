package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent application user interface, as DescribePersistentAppUI reports it, with the cluster it was made for and
 * the Region it is in.
 */
@RegisterForReflection
public class EmrPersistentAppUi {

    private String id;
    private String clusterId;
    private List<String> types = new ArrayList<>();
    private String status;
    private String authorId;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private String lastStateChangeReason;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public EmrPersistentAppUi() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }
    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
    public String getLastStateChangeReason() { return lastStateChangeReason; }
    public void setLastStateChangeReason(String lastStateChangeReason) {
        this.lastStateChangeReason = lastStateChangeReason;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
