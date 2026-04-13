package io.github.hectorvent.floci.services.iam.model;

import java.util.List;
import java.util.Map;

/**
 * A single parsed statement from an IAM policy document.
 *
 * <p>Supports Phase 1 (actions, resources), Phase 4 (NotAction, NotResource, Condition).
 */
public class PolicyStatement {

    private final String effect;            // "Allow" or "Deny"
    private final List<String> actions;     // IAM action patterns; null when notActions is set
    private final List<String> notActions;  // NotAction patterns; null when actions is set
    private final List<String> resources;   // resource ARN patterns; null when notResources is set
    private final List<String> notResources;// NotResource patterns; null when resources is set
    // Condition: outer key = operator (e.g. "StringEquals"), inner key = context key, value = list of values
    private final Map<String, Map<String, List<String>>> conditions;

    public PolicyStatement(String effect,
                           List<String> actions,
                           List<String> notActions,
                           List<String> resources,
                           List<String> notResources,
                           Map<String, Map<String, List<String>>> conditions) {
        this.effect = effect;
        this.actions = actions;
        this.notActions = notActions;
        this.resources = resources;
        this.notResources = notResources;
        this.conditions = conditions;
    }

    /** Convenience constructor for simple allow/deny without conditions or Not* fields. */
    public PolicyStatement(String effect, List<String> actions, List<String> resources) {
        this(effect, actions, null, resources, null, null);
    }

    public String getEffect()              { return effect; }
    public List<String> getActions()       { return actions; }
    public List<String> getNotActions()    { return notActions; }
    public List<String> getResources()     { return resources; }
    public List<String> getNotResources()  { return notResources; }
    public Map<String, Map<String, List<String>>> getConditions() { return conditions; }

    public boolean isDeny()  { return "Deny".equalsIgnoreCase(effect); }
    public boolean isAllow() { return "Allow".equalsIgnoreCase(effect); }
}
