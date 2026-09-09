package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentTarget {
    private String resourceType;
    private List<String> resourceArns;
    private Map<String, String> resourceTags;
    private List<TargetFilter> filters;
    private String selectionMode;
    private Map<String, String> parameters;

    public ExperimentTarget() {
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public List<String> getResourceArns() {
        return resourceArns;
    }

    public void setResourceArns(List<String> resourceArns) {
        this.resourceArns = resourceArns;
    }

    public Map<String, String> getResourceTags() {
        return resourceTags;
    }

    public void setResourceTags(Map<String, String> resourceTags) {
        this.resourceTags = resourceTags;
    }

    public List<TargetFilter> getFilters() {
        return filters;
    }

    public void setFilters(List<TargetFilter> filters) {
        this.filters = filters;
    }

    public String getSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(String selectionMode) {
        this.selectionMode = selectionMode;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }
}
