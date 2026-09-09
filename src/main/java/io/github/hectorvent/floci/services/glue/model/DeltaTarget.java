package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class DeltaTarget {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("CreateNativeDeltaTable")
    private Boolean createNativeDeltaTable;

    @JsonProperty("DeltaTables")
    private List<String> deltaTables;

    @JsonProperty("WriteManifest")
    private Boolean writeManifest;

    public DeltaTarget() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public Boolean getCreateNativeDeltaTable() { return createNativeDeltaTable; }
    public void setCreateNativeDeltaTable(Boolean createNativeDeltaTable) { this.createNativeDeltaTable = createNativeDeltaTable; }

    public List<String> getDeltaTables() { return deltaTables; }
    public void setDeltaTables(List<String> deltaTables) { this.deltaTables = deltaTables; }

    public Boolean getWriteManifest() { return writeManifest; }
    public void setWriteManifest(Boolean writeManifest) { this.writeManifest = writeManifest; }
}
