package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One weighted state-machine version target in an alias routing configuration. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfiguration {
    private String stateMachineVersionArn;
    private int weight;

    public RoutingConfiguration() {
    }

    public RoutingConfiguration(String stateMachineVersionArn, int weight) {
        this.stateMachineVersionArn = stateMachineVersionArn;
        this.weight = weight;
    }

    public String getStateMachineVersionArn() { return stateMachineVersionArn; }
    public void setStateMachineVersionArn(String stateMachineVersionArn) {
        this.stateMachineVersionArn = stateMachineVersionArn;
    }

    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
}
