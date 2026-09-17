package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchNodePropertyOverride {
    private String targetNodes;
    private BatchContainerOverrides containerOverrides;

    public String getTargetNodes() {
        return targetNodes;
    }

    public void setTargetNodes(String targetNodes) {
        this.targetNodes = targetNodes;
    }

    public BatchContainerOverrides getContainerOverrides() {
        return containerOverrides;
    }

    public void setContainerOverrides(BatchContainerOverrides containerOverrides) {
        this.containerOverrides = containerOverrides;
    }
}
