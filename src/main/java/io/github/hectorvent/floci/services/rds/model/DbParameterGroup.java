package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class DbParameterGroup {

    private String dbParameterGroupName;
    private String dbParameterGroupFamily;
    private String description;
    private String region;
    private Map<String, String> parameters = new HashMap<>();

    public DbParameterGroup() {}

    public DbParameterGroup(String dbParameterGroupName, String dbParameterGroupFamily,
                            String description) {
        this.dbParameterGroupName = dbParameterGroupName;
        this.dbParameterGroupFamily = dbParameterGroupFamily;
        this.description = description;
    }

    public String getDbParameterGroupName() { return dbParameterGroupName; }
    public void setDbParameterGroupName(String dbParameterGroupName) { this.dbParameterGroupName = dbParameterGroupName; }

    public String getDbParameterGroupFamily() { return dbParameterGroupFamily; }
    public void setDbParameterGroupFamily(String dbParameterGroupFamily) { this.dbParameterGroupFamily = dbParameterGroupFamily; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
}
