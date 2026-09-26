package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Trigger {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("WorkflowName")
    private String workflowName;

    @JsonProperty("Type")
    private String type;

    @JsonProperty("State")
    private String state;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Schedule")
    private String schedule;

    @JsonProperty("Actions")
    private List<TriggerAction> actions;

    @JsonProperty("Predicate")
    private Predicate predicate;

    @JsonProperty("EventBatchingCondition")
    private EventBatchingCondition eventBatchingCondition;

    public Trigger() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public List<TriggerAction> getActions() { return actions; }
    public void setActions(List<TriggerAction> actions) { this.actions = actions; }

    public Predicate getPredicate() { return predicate; }
    public void setPredicate(Predicate predicate) { this.predicate = predicate; }

    public EventBatchingCondition getEventBatchingCondition() { return eventBatchingCondition; }
    public void setEventBatchingCondition(EventBatchingCondition eventBatchingCondition) { this.eventBatchingCondition = eventBatchingCondition; }
}
