package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class ApiGatewayResource {

    private String id;
    private String apiId;
    private String parentId;
    private String pathPart;
    private String path;
    private Map<String, MethodConfig> resourceMethods = new ConcurrentHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getPathPart() { return pathPart; }
    public void setPathPart(String pathPart) { this.pathPart = pathPart; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Map<String, MethodConfig> getResourceMethods() { return resourceMethods; }
    public void setResourceMethods(Map<String, MethodConfig> resourceMethods) {
        this.resourceMethods = resourceMethods != null ? new ConcurrentHashMap<>(resourceMethods) : new ConcurrentHashMap<>();
    }
}
