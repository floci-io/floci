package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchNodeProperties {
    private Integer numNodes;
    private Integer mainNode;
    private List<BatchNodeRangeProperty> nodeRangeProperties = new ArrayList<>();

    public Integer getNumNodes() {
        return numNodes;
    }

    public void setNumNodes(Integer numNodes) {
        this.numNodes = numNodes;
    }

    public Integer getMainNode() {
        return mainNode;
    }

    public void setMainNode(Integer mainNode) {
        this.mainNode = mainNode;
    }

    public List<BatchNodeRangeProperty> getNodeRangeProperties() {
        return nodeRangeProperties;
    }

    public void setNodeRangeProperties(List<BatchNodeRangeProperty> nodeRangeProperties) {
        this.nodeRangeProperties = nodeRangeProperties != null ? nodeRangeProperties : new ArrayList<>();
    }
}
