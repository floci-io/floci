package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private Map<String, String> tags = new LinkedHashMap<>();

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    private List<Parameter> parameters = new ArrayList<>(List.of(
            new Parameter("max_cursor_result_set_size", "0", "Maximum cursor result set size", "integer"),
            new Parameter("wlm_json_configuration", "{}", "WLM configuration", "string")
    ));

    public List<Parameter> getParameters() { return parameters; }
    public void setParameters(List<Parameter> parameters) { this.parameters = parameters; }
}
