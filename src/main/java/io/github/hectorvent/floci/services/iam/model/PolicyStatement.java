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
    private final Map<String, List<String>> principals;    // Principal patterns by type; null when not set
    private final Map<String, List<String>> notPrincipals; // NotPrincipal patterns by type; null when not set
    private final List<String> actions;     // IAM action patterns; null when notActions is set
    private final List<String> notActions;  // NotAction patterns; null when actions is set
    private final List<String> resources;   // resource ARN patterns; null when notResources is set
    private final List<String> notResources;// NotResource patterns; null when resources is set
    // Condition: outer key = operator (e.g. "StringEquals"), inner key = context key, value = list of values
    private final Map<String, Map<String, List<String>>> conditions;
    // The document's Version element, which decides whether this statement's ${...} are policy
    // variables or literal text. It belongs to the document rather than the statement, but
    // statements from many documents are evaluated as one list, so each carries its own. Null
    // when the document has no Version element, which is the 2008-10-17 default.
    private final String version;

    public PolicyStatement(String effect,
                           Map<String, List<String>> principals,
                           Map<String, List<String>> notPrincipals,
                           List<String> actions,
                           List<String> notActions,
                           List<String> resources,
                           List<String> notResources,
                           Map<String, Map<String, List<String>>> conditions,
                           String version) {
        this.effect = effect;
        this.principals = principals;
        this.notPrincipals = notPrincipals;
        this.actions = actions;
        this.notActions = notActions;
        this.resources = resources;
        this.notResources = notResources;
        this.conditions = conditions;
        this.version = version;
    }

    /** Without a version, {@code ${...}} stays literal text, as it does under 2008-10-17. */
    public PolicyStatement(String effect,
                           List<String> actions,
                           List<String> notActions,
                           List<String> resources,
                           List<String> notResources,
                           Map<String, Map<String, List<String>>> conditions) {
        this(effect, null, null, actions, notActions, resources, notResources, conditions, null);
    }

    /** Convenience constructor for simple allow/deny without conditions or Not* fields. */
    public PolicyStatement(String effect, List<String> actions, List<String> resources) {
        this(effect, null, null, actions, null, resources, null, null, null);
    }

    public String getEffect()              { return effect; }
    public Map<String, List<String>> getPrincipals()    { return principals; }
    public Map<String, List<String>> getNotPrincipals() { return notPrincipals; }
    public List<String> getActions()       { return actions; }
    public List<String> getNotActions()    { return notActions; }
    public List<String> getResources()     { return resources; }
    public List<String> getNotResources()  { return notResources; }
    public Map<String, Map<String, List<String>>> getConditions() { return conditions; }
    public String getVersion()             { return version; }

    public boolean isDeny()  { return "Deny".equalsIgnoreCase(effect); }
    public boolean isAllow() { return "Allow".equalsIgnoreCase(effect); }
}
