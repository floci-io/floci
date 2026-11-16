package io.floci.aws.services.resourcegroupstagging.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceTagMapping {

    private String resourceArn;
    // Preserves insertion order for deterministic responses. A stored mapping's tags are replaced, never changed in
    // place, so a reader copying them sees one whole set.
    private volatile Map<String, String> tags = new LinkedHashMap<>();

    public ResourceTagMapping() {}

    public ResourceTagMapping(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : tags; }
}
