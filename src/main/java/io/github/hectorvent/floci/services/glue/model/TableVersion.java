package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TableVersion {
    @JsonProperty("Table")
    private Table table;
    @JsonProperty("VersionId")
    private String versionId;

    public TableVersion() {}

    public TableVersion(Table table, String versionId) {
        this.table = table;
        this.versionId = versionId;
    }

    public Table getTable() { return table; }
    public void setTable(Table table) { this.table = table; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
}
