package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class RedshiftParameterGroup {

    private String parameterGroupName;
    private String parameterGroupFamily;
    private String description;
    private Map<String, RedshiftParameter> parameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getParameterGroupName() { return parameterGroupName; }
    public void setParameterGroupName(String parameterGroupName) { this.parameterGroupName = parameterGroupName; }

    public String getParameterGroupFamily() { return parameterGroupFamily; }
    public void setParameterGroupFamily(String parameterGroupFamily) {
        this.parameterGroupFamily = parameterGroupFamily;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, RedshiftParameter> getParameters() { return parameters; }
    public void setParameters(Map<String, RedshiftParameter> parameters) { this.parameters = parameters; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
