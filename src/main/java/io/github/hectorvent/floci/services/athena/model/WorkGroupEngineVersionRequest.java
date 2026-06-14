package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WorkGroupEngineVersionRequest {

    @JsonProperty("SelectedEngineVersion")
    private String selectedEngineVersion;

    @JsonProperty("EffectiveEngineVersion")
    private String effectiveEngineVersion;

    public WorkGroupEngineVersionRequest() {}

    public String getSelectedEngineVersion() { return selectedEngineVersion; }
    public void setSelectedEngineVersion(String selectedEngineVersion) {
        this.selectedEngineVersion = selectedEngineVersion;
    }
    public String getEffectiveEngineVersion() { return effectiveEngineVersion; }
    public void setEffectiveEngineVersion(String effectiveEngineVersion) {
        this.effectiveEngineVersion = effectiveEngineVersion;
    }
}
