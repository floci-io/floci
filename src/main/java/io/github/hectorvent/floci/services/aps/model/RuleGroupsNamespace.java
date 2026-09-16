package io.github.hectorvent.floci.services.aps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleGroupsNamespace {

    private String name;
    private String workspaceId;
    private String arn;
    private String status;
    private String encodedData;
    private Instant createdAt;
    private Instant modifiedAt;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public RuleGroupsNamespace() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEncodedData() { return encodedData; }
    public void setEncodedData(String encodedData) { this.encodedData = encodedData; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new ConcurrentHashMap<>(tags) : new ConcurrentHashMap<>();
    }
}
