package io.github.hectorvent.flociappsync.iam.model;

import java.util.List;
import java.util.Map;

/**
 * A single parsed statement from an IAM policy document. Ported verbatim from Floci's
 * {@code io.github.hectorvent.floci.services.iam.model.PolicyStatement}.
 */
public class PolicyStatement {

    private final String effect;
    private final List<String> actions;
    private final List<String> notActions;
    private final List<String> resources;
    private final List<String> notResources;
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

    public String getEffect()              { return effect; }
    public List<String> getActions()       { return actions; }
    public List<String> getNotActions()    { return notActions; }
    public List<String> getResources()     { return resources; }
    public List<String> getNotResources()  { return notResources; }
    public Map<String, Map<String, List<String>>> getConditions() { return conditions; }

    public boolean isDeny()  { return "Deny".equalsIgnoreCase(effect); }
    public boolean isAllow() { return "Allow".equalsIgnoreCase(effect); }
}
