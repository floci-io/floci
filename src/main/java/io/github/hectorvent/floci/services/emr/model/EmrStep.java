package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An EMR job-flow step. State machine: PENDING → RUNNING → COMPLETED (or CANCELLED/FAILED). A step the
 * {@code docker} runner mode started runs in a container, and a FAILED one carries its FailureDetails.
 */
@RegisterForReflection
public class EmrStep {

    private String id;
    private String name;
    private String jar;
    private String mainClass;
    private List<String> args = new ArrayList<>();
    private Map<String, String> properties = new LinkedHashMap<>();
    private String actionOnFailure;
    private String state;
    private String stateChangeReason;
    private String executionRoleArn;
    private boolean runInContainer;
    private String failureReason;
    private String failureMessage;
    private Instant creationDateTime;
    private Instant startDateTime;
    private Instant endDateTime;

    public EmrStep() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getJar() { return jar; }
    public void setJar(String jar) { this.jar = jar; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> properties) { this.properties = properties; }

    public String getActionOnFailure() { return actionOnFailure; }
    public void setActionOnFailure(String actionOnFailure) { this.actionOnFailure = actionOnFailure; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateChangeReason() { return stateChangeReason; }
    public void setStateChangeReason(String stateChangeReason) { this.stateChangeReason = stateChangeReason; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public boolean isRunInContainer() { return runInContainer; }
    public void setRunInContainer(boolean runInContainer) { this.runInContainer = runInContainer; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public Instant getStartDateTime() { return startDateTime; }
    public void setStartDateTime(Instant startDateTime) { this.startDateTime = startDateTime; }

    public Instant getEndDateTime() { return endDateTime; }
    public void setEndDateTime(Instant endDateTime) { this.endDateTime = endDateTime; }
}
