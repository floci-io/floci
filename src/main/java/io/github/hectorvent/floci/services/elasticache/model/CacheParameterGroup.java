package io.github.hectorvent.floci.services.elasticache.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheParameterGroup {

    private String name;
    private String family;
    private String description;
    /** Only the parameters a caller set; floci does not carry AWS's per-family defaults. */
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public CacheParameterGroup() {
    }

    public CacheParameterGroup(String name, String family, String description) {
        this.name = name;
        this.family = family;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : parameters;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
