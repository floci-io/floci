package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationSet {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    @JsonProperty("EventDestinations")
    private List<EventDestination> eventDestinations = new ArrayList<>();

    public ConfigurationSet() {}

    public ConfigurationSet(String name) {
        this.name = name;
        this.createdTimestamp = Instant.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public List<EventDestination> getEventDestinations() { return eventDestinations; }
    public void setEventDestinations(List<EventDestination> eventDestinations) {
        this.eventDestinations = eventDestinations != null ? eventDestinations : new ArrayList<>();
    }
}
