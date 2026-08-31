package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ConfigurationRevision {

    @JsonProperty("revision")
    private long revision;

    @JsonProperty("creationTime")
    private Instant creationTime;

    @JsonProperty("description")
    private String description;

    public ConfigurationRevision() {}

    public ConfigurationRevision(long revision, Instant creationTime, String description) {
        this.revision = revision;
        this.creationTime = creationTime;
        this.description = description;
    }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
