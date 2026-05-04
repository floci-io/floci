package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class Archive {

    private String archiveName;
    private String archiveArn;
    private String eventSourceArn;
    private String description;
    private String eventPattern;
    private int retentionDays;
    private ArchiveState state;
    private String stateReason;
    private long eventCount;
    private long sizeBytes;
    private Instant creationTime;
    private Map<String, String> tags = new HashMap<>();

    public Archive() {}

    public String getArchiveName() { return archiveName; }
    public void setArchiveName(String archiveName) { this.archiveName = archiveName; }

    public String getArchiveArn() { return archiveArn; }
    public void setArchiveArn(String archiveArn) { this.archiveArn = archiveArn; }

    public String getEventSourceArn() { return eventSourceArn; }
    public void setEventSourceArn(String eventSourceArn) { this.eventSourceArn = eventSourceArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEventPattern() { return eventPattern; }
    public void setEventPattern(String eventPattern) { this.eventPattern = eventPattern; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public ArchiveState getState() { return state; }
    public void setState(ArchiveState state) { this.state = state; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public long getEventCount() { return eventCount; }
    public void setEventCount(long eventCount) { this.eventCount = eventCount; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
