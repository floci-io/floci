package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class ScheduleGroup {

    private String name;
    private String arn;
    private String state;
    private Instant creationDate;
    private Instant lastModificationDate;
    private Map<String, String> tags = new HashMap<>();

    public ScheduleGroup() {}

    public ScheduleGroup(String name, String arn, String state,
                         Instant creationDate, Instant lastModificationDate) {
        this.name = name;
        this.arn = arn;
        this.state = state;
        this.creationDate = creationDate;
        this.lastModificationDate = lastModificationDate;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public Instant getLastModificationDate() { return lastModificationDate; }
    public void setLastModificationDate(Instant lastModificationDate) { this.lastModificationDate = lastModificationDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
