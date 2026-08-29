package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogGroup {

    private String logGroupName;
    private long createdTime;
    private Integer retentionInDays;
    private boolean deletionProtectionEnabled;
    private String kmsKeyId;
    private Map<String, String> tags = new HashMap<>();

    public LogGroup() {}

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

    public Integer getRetentionInDays() { return retentionInDays; }
    public void setRetentionInDays(Integer retentionInDays) { this.retentionInDays = retentionInDays; }

    public boolean isDeletionProtectionEnabled() { return deletionProtectionEnabled; }
    public void setDeletionProtectionEnabled(boolean deletionProtectionEnabled) {
        this.deletionProtectionEnabled = deletionProtectionEnabled;
    }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
