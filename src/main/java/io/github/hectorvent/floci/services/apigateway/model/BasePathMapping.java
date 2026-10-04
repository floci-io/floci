package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BasePathMapping {
    private String basePath;
    private String restApiId;
    private String stage;
    private String apiType;
    /**
     * The v2 {@code apiMappingId}, given when the mapping is created and kept when its key changes.
     * Null on a record persisted before ids were stored, whose id is derived from its stored path.
     */
    private String apiMappingId;

    public BasePathMapping() {
        this.basePath = "(none)";
    }

    public BasePathMapping(String basePath, String restApiId, String stage) {
        this.basePath = (basePath == null || basePath.isEmpty()) ? "(none)" : basePath;
        this.restApiId = restApiId;
        this.stage = stage;
    }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public String getRestApiId() { return restApiId; }
    public void setRestApiId(String restApiId) { this.restApiId = restApiId; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getApiType() { return apiType; }
    public void setApiType(String apiType) { this.apiType = apiType; }

    public String getApiMappingId() { return apiMappingId; }
    public void setApiMappingId(String apiMappingId) { this.apiMappingId = apiMappingId; }
}
