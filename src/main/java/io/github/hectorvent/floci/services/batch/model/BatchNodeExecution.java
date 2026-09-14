package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

// The resolved, per-node execution spec and runtime result for one node of a multi-node job.
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchNodeExecution {
    private int nodeIndex;
    private boolean mainNode;
    private String containerImage;
    private List<String> resolvedCommand = new ArrayList<>();
    private List<BatchKeyValue> resolvedEnvironment = new ArrayList<>();
    private List<BatchResourceRequirement> resourceRequirements = new ArrayList<>();
    private BatchAttemptContainer container;

    public int getNodeIndex() {
        return nodeIndex;
    }

    public void setNodeIndex(int nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    public boolean isMainNode() {
        return mainNode;
    }

    public void setMainNode(boolean mainNode) {
        this.mainNode = mainNode;
    }

    public String getContainerImage() {
        return containerImage;
    }

    public void setContainerImage(String containerImage) {
        this.containerImage = containerImage;
    }

    public List<String> getResolvedCommand() {
        return resolvedCommand;
    }

    public void setResolvedCommand(List<String> resolvedCommand) {
        this.resolvedCommand = resolvedCommand != null ? resolvedCommand : new ArrayList<>();
    }

    public List<BatchKeyValue> getResolvedEnvironment() {
        return resolvedEnvironment;
    }

    public void setResolvedEnvironment(List<BatchKeyValue> resolvedEnvironment) {
        this.resolvedEnvironment = resolvedEnvironment != null ? resolvedEnvironment : new ArrayList<>();
    }

    public List<BatchResourceRequirement> getResourceRequirements() {
        return resourceRequirements;
    }

    public void setResourceRequirements(List<BatchResourceRequirement> resourceRequirements) {
        this.resourceRequirements = resourceRequirements != null ? resourceRequirements : new ArrayList<>();
    }

    public BatchAttemptContainer getContainer() {
        return container;
    }

    public void setContainer(BatchAttemptContainer container) {
        this.container = container;
    }
}
