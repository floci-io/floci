package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DbClusterParameterGroup {

    private String dbClusterParameterGroupArn;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String dbClusterParameterGroupName;
    private String dbParameterGroupFamily;
    private String description;
    private String region;
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

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }

    public Map<String, String> getTags() { return tags; }

    /** Normalizes null: a record persisted before tags were stored deserializes without them. */
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getDbClusterParameterGroupArn() { return dbClusterParameterGroupArn; }
    public void setDbClusterParameterGroupArn(String dbClusterParameterGroupArn) { this.dbClusterParameterGroupArn = dbClusterParameterGroupArn; }
}
