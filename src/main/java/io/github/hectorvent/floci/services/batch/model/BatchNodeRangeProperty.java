package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchNodeRangeProperty {
    private String targetNodes;
    private BatchContainerProperties container;

    public String getTargetNodes() {
        return targetNodes;
    }

    public void setTargetNodes(String targetNodes) {
        this.targetNodes = targetNodes;
    }

    public BatchContainerProperties getContainer() {
        return container;
    }

    public void setContainer(BatchContainerProperties container) {
        this.container = container;
    }
}
