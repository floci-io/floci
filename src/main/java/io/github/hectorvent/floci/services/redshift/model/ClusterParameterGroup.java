package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ClusterParameterGroup {
    private String parameterGroupName;
    private String parameterGroupFamily;
    private String description;

    public ClusterParameterGroup() {}

    public ClusterParameterGroup(String parameterGroupName, String parameterGroupFamily, String description) {
        this.parameterGroupName = parameterGroupName;
        this.parameterGroupFamily = parameterGroupFamily;
        this.description = description;
    }

    public String getParameterGroupName() {
        return parameterGroupName;
    }

    public void setParameterGroupName(String parameterGroupName) {
        this.parameterGroupName = parameterGroupName;
    }

    public String getParameterGroupFamily() {
        return parameterGroupFamily;
    }

    public void setParameterGroupFamily(String parameterGroupFamily) {
        this.parameterGroupFamily = parameterGroupFamily;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
