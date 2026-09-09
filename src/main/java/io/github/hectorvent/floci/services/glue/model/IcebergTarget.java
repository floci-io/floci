package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class IcebergTarget {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("Exclusions")
    private List<String> exclusions;

    @JsonProperty("MaximumTraversalDepth")
    private Integer maximumTraversalDepth;

    @JsonProperty("Paths")
    private List<String> paths;

    public IcebergTarget() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public List<String> getExclusions() { return exclusions; }
    public void setExclusions(List<String> exclusions) { this.exclusions = exclusions; }

    public Integer getMaximumTraversalDepth() { return maximumTraversalDepth; }
    public void setMaximumTraversalDepth(Integer maximumTraversalDepth) { this.maximumTraversalDepth = maximumTraversalDepth; }

    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths; }
}
