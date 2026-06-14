package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ResourceUri {
    @JsonProperty("ResourceType")
    private String resourceType;
    @JsonProperty("Uri")
    private String uri;

    public ResourceUri() {}

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
}
