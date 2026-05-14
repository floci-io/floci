package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class DbClusterParameterGroup {

    private String dbClusterParameterGroupName;
    private String dbParameterGroupFamily;
    private String description;
    private Map<String, String> parameters = new HashMap<>();

    public DbClusterParameterGroup() {}

    public DbClusterParameterGroup(String dbClusterParameterGroupName, String dbParameterGroupFamily,
                                   String description) {
        this.dbClusterParameterGroupName = dbClusterParameterGroupName;
        this.dbParameterGroupFamily = dbParameterGroupFamily;
        this.description = description;
    }

    public String getDbClusterParameterGroupName() { return dbClusterParameterGroupName; }
    public void setDbClusterParameterGroupName(String dbClusterParameterGroupName) { this.dbClusterParameterGroupName = dbClusterParameterGroupName; }

    public String getDbParameterGroupFamily() { return dbParameterGroupFamily; }
    public void setDbParameterGroupFamily(String dbParameterGroupFamily) { this.dbParameterGroupFamily = dbParameterGroupFamily; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
}
