package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Execution {
    private String executionArn;
    private String stateMachineArn;
    private String name;
    private String status = "RUNNING"; // RUNNING, SUCCEEDED, FAILED, TIMED_OUT, ABORTED
    private String input;
    private String output;
    private double startDate;
    private Double stopDate;
    private String error;
    private String cause;

    public Execution() {
        this.startDate = System.currentTimeMillis() / 1000.0;
    }

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
}
