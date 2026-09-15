package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchNodeOverrides {
    private Integer numNodes;
    private List<BatchNodePropertyOverride> nodePropertyOverrides = new ArrayList<>();

    public Integer getNumNodes() {
        return numNodes;
    }

    public void setNumNodes(Integer numNodes) {
        this.numNodes = numNodes;
    }

    public List<BatchNodePropertyOverride> getNodePropertyOverrides() {
        return nodePropertyOverrides;
    }

    public void setNodePropertyOverrides(List<BatchNodePropertyOverride> nodePropertyOverrides) {
        this.nodePropertyOverrides = nodePropertyOverrides != null ? nodePropertyOverrides : new ArrayList<>();
    }
}
