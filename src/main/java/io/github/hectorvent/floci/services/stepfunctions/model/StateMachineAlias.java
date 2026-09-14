package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A named pointer to one or two published versions of a state machine. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachineAlias {
    private String stateMachineAliasArn;
    private String stateMachineArn;
    private String name;
    private String description;
    private List<RoutingConfiguration> routingConfiguration = new ArrayList<>();
    private double creationDate;
    private double updateDate;

    public StateMachineAlias() {
        creationDate = System.currentTimeMillis() / 1000.0;
        updateDate = creationDate;
    }

    public String getStateMachineAliasArn() { return stateMachineAliasArn; }
    public void setStateMachineAliasArn(String stateMachineAliasArn) {
        this.stateMachineAliasArn = stateMachineAliasArn;
    }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<RoutingConfiguration> getRoutingConfiguration() { return routingConfiguration; }
    public void setRoutingConfiguration(List<RoutingConfiguration> routingConfiguration) {
        this.routingConfiguration = routingConfiguration != null
                ? routingConfiguration : new ArrayList<>();
    }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }

    public double getUpdateDate() { return updateDate; }
    public void setUpdateDate(double updateDate) { this.updateDate = updateDate; }
}
