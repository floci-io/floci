package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class SchemaChangePolicy {
    @JsonProperty("DeleteBehavior")
    private String deleteBehavior;

    @JsonProperty("UpdateBehavior")
    private String updateBehavior;

    public SchemaChangePolicy() {}

    public String getDeleteBehavior() { return deleteBehavior; }
    public void setDeleteBehavior(String deleteBehavior) { this.deleteBehavior = deleteBehavior; }

    public String getUpdateBehavior() { return updateBehavior; }
    public void setUpdateBehavior(String updateBehavior) { this.updateBehavior = updateBehavior; }
}
