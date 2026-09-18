package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKey {
    private String id;
    private String name;
    private String value;
    private String customerId;
    private boolean enabled;
    private long createdDate;
    private long lastUpdatedDate;
    private String description;
    private List<String> stageKeys = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();

    public ApiKey() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastUpdatedDate() { return lastUpdatedDate; }
    public void setLastUpdatedDate(long lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getStageKeys() { return stageKeys; }
    public void setStageKeys(List<String> stageKeys) { this.stageKeys = stageKeys != null ? stageKeys : new ArrayList<>(); }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
