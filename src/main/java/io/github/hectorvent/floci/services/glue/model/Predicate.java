package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Predicate {
    @JsonProperty("Logical")
    private String logical;

    @JsonProperty("Conditions")
    private List<TriggerCondition> conditions;

    public Predicate() {}

    public String getLogical() { return logical; }
    public void setLogical(String logical) { this.logical = logical; }

    public List<TriggerCondition> getConditions() { return conditions; }
    public void setConditions(List<TriggerCondition> conditions) { this.conditions = conditions; }
}
