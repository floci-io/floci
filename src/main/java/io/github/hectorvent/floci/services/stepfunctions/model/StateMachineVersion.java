package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A published version of a state machine ({@code <stateMachineArn>:<version>}). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachineVersion {
    private String stateMachineVersionArn;
    private int version;
    private double creationDate;

    public StateMachineVersion() {
    }

    public StateMachineVersion(String stateMachineVersionArn, int version, double creationDate) {
        this.stateMachineVersionArn = stateMachineVersionArn;
        this.version = version;
        this.creationDate = creationDate;
    }

    public String getStateMachineVersionArn() { return stateMachineVersionArn; }
    public void setStateMachineVersionArn(String stateMachineVersionArn) { this.stateMachineVersionArn = stateMachineVersionArn; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }
}
