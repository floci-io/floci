package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class JdbcTarget {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("EnableAdditionalMetadata")
    private List<String> enableAdditionalMetadata;

    @JsonProperty("Exclusions")
    private List<String> exclusions;

    @JsonProperty("Path")
    private String path;

    public JdbcTarget() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public List<String> getEnableAdditionalMetadata() { return enableAdditionalMetadata; }
    public void setEnableAdditionalMetadata(List<String> enableAdditionalMetadata) { this.enableAdditionalMetadata = enableAdditionalMetadata; }

    public List<String> getExclusions() { return exclusions; }
    public void setExclusions(List<String> exclusions) { this.exclusions = exclusions; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
