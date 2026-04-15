package io.github.hectorvent.floci.services.resourcegroupstagging.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceTagMapping {

    private String resourceArn;
    // Preserves insertion order for deterministic responses
    private final Map<String, String> tags = new LinkedHashMap<>();

    public ResourceTagMapping() {}

    public ResourceTagMapping(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public Map<String, String> getTags() { return tags; }
}
