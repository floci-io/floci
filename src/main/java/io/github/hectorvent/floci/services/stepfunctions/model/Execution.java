package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Execution {
    private String executionArn;
    private String stateMachineArn;
    private String stateMachineVersionArn;
    private String stateMachineAliasArn;
    private String name;
    private String status = "RUNNING"; // RUNNING, SUCCEEDED, FAILED, TIMED_OUT, ABORTED
    private String input;
    private String output;
    private double startDate;
    private Double stopDate;
    private String error;
    private String cause;
    private List<HistoryEvent> history = new ArrayList<>();
    @JsonIgnore
    private transient Set<ExecutionStatusSubscription> statusSubscriptions;

    public Execution() {
        this.startDate = System.currentTimeMillis() / 1000.0;
    }

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getStateMachineVersionArn() { return stateMachineVersionArn; }
    public void setStateMachineVersionArn(String stateMachineVersionArn) {
        this.stateMachineVersionArn = stateMachineVersionArn;
    }

    public String getStateMachineAliasArn() { return stateMachineAliasArn; }
    public void setStateMachineAliasArn(String stateMachineAliasArn) {
        this.stateMachineAliasArn = stateMachineAliasArn;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public synchronized void setStatus(String status) {
        this.status = status;
        if (!"RUNNING".equals(status) && statusSubscriptions != null) {
            Set<ExecutionStatusSubscription> subscriptions = statusSubscriptions;
            statusSubscriptions = null;
            for (ExecutionStatusSubscription subscription : subscriptions) {
                subscription.signal();
            }
        }
    }

    synchronized void registerStatusSubscription(ExecutionStatusSubscription subscription) {
        if (!"RUNNING".equals(status)) {
            subscription.signal();
            return;
        }
        if (statusSubscriptions == null) {
            statusSubscriptions = new HashSet<>();
        }
        statusSubscriptions.add(subscription);
    }

    synchronized void unregisterStatusSubscription(ExecutionStatusSubscription subscription) {
        if (statusSubscriptions != null) {
            statusSubscriptions.remove(subscription);
            if (statusSubscriptions.isEmpty()) {
                statusSubscriptions = null;
            }
        }
    }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public double getStartDate() { return startDate; }
    public void setStartDate(double startDate) { this.startDate = startDate; }

    public Double getStopDate() { return stopDate; }
    public void setStopDate(Double stopDate) { this.stopDate = stopDate; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }

    public List<HistoryEvent> getHistory() { return history; }
    public void setHistory(List<HistoryEvent> history) {
        this.history = history != null ? history : new ArrayList<>();
    }
}
