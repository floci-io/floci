package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MongoDBTarget {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("Path")
    private String path;

    @JsonProperty("ScanAll")
    private Boolean scanAll;

    public MongoDBTarget() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Boolean getScanAll() { return scanAll; }
    public void setScanAll(Boolean scanAll) { this.scanAll = scanAll; }
}
