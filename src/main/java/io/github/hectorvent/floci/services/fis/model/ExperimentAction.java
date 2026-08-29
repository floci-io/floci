package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentAction {
    private String actionId;
    private String description;
    private Map<String, String> parameters;
    private Map<String, String> targets;
    private List<String> startAfter;
    private ExperimentActionState state;
    private Double startTime;
    private Double endTime;

    public ExperimentAction() {
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public Map<String, String> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets;
    }

    public List<String> getStartAfter() {
        return startAfter;
    }

    public void setStartAfter(List<String> startAfter) {
        this.startAfter = startAfter;
    }

    public ExperimentActionState getState() {
        return state;
    }

    public void setState(ExperimentActionState state) {
        this.state = state;
    }

    public Double getStartTime() {
        return startTime;
    }

    public void setStartTime(Double startTime) {
        this.startTime = startTime;
    }

    public Double getEndTime() {
        return endTime;
    }

    public void setEndTime(Double endTime) {
        this.endTime = endTime;
    }
}
