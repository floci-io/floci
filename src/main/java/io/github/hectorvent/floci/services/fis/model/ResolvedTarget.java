package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResolvedTarget {
    private String resourceType;
    private String targetName;
    private Map<String, String> targetInformation;

    public ResolvedTarget() {
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public Map<String, String> getTargetInformation() {
        return targetInformation;
    }

    public void setTargetInformation(Map<String, String> targetInformation) {
        this.targetInformation = targetInformation;
    }
}
