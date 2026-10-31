package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot schedule. Associated clusters are not stored here: each cluster records the
 * schedule it uses, so deleting a cluster cannot leave a stale association behind.
 */
@RegisterForReflection
public class SnapshotSchedule {
    private String scheduleIdentifier;
    private String scheduleDescription;
    private List<String> scheduleDefinitions = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Instant updatedAt;

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }

    public SnapshotSchedule() {}

    public SnapshotSchedule(String scheduleIdentifier, String scheduleDescription, List<String> scheduleDefinitions) {
        this.scheduleIdentifier = scheduleIdentifier;
        this.scheduleDescription = scheduleDescription;
        this.scheduleDefinitions = new ArrayList<>(scheduleDefinitions);
    }

    public String getScheduleIdentifier() { return scheduleIdentifier; }
    public void setScheduleIdentifier(String scheduleIdentifier) { this.scheduleIdentifier = scheduleIdentifier; }
    public String getScheduleDescription() { return scheduleDescription; }
    public void setScheduleDescription(String scheduleDescription) { this.scheduleDescription = scheduleDescription; }
    public List<String> getScheduleDefinitions() { return scheduleDefinitions; }
    public void setScheduleDefinitions(List<String> scheduleDefinitions) { this.scheduleDefinitions = scheduleDefinitions; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
