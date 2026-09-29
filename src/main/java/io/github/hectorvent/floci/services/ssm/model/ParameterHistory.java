package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParameterHistory {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Version")
    private long version;

    @JsonProperty("Value")
    private String value;

    @JsonProperty("Type")
    private String type;

    @JsonProperty("LastModifiedDate")
    private Instant lastModifiedDate;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Labels")
    private List<String> labels = new ArrayList<>();

    public ParameterHistory() {}

    public ParameterHistory(Parameter parameter) {
        this.name = parameter.getName();
        this.version = parameter.getVersion();
        this.value = parameter.getValue();
        this.type = parameter.getType();
        this.lastModifiedDate = parameter.getLastModifiedDate();
        this.description = parameter.getDescription();
    }

    public ParameterHistory(ParameterHistory other) {
        this.name = other.name;
        this.version = other.version;
        this.value = other.value;
        this.type = other.type;
        this.lastModifiedDate = other.lastModifiedDate;
        this.description = other.description;
        this.labels = other.labels != null ? new ArrayList<>(other.labels) : new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(Instant lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }
}
