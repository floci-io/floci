package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachine {
    private String stateMachineArn;
    private String name;
    private String definition;
    private String roleArn;
    private String type = "STANDARD";
    private String status = "ACTIVE";
    private double creationDate;

    public StateMachine() {
        this.creationDate = System.currentTimeMillis() / 1000.0;
    }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }
}
