package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {

    private String ruleArn;
    private String listenerArn;
    private String priority;
    private List<RuleCondition> conditions = new ArrayList<>();
    private List<Action> actions = new ArrayList<>();
    private boolean isDefault;

    public Rule() {}

    public String getRuleArn() { return ruleArn; }
    public void setRuleArn(String ruleArn) { this.ruleArn = ruleArn; }

    public String getListenerArn() { return listenerArn; }
    public void setListenerArn(String listenerArn) { this.listenerArn = listenerArn; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public List<RuleCondition> getConditions() { return conditions; }
    public void setConditions(List<RuleCondition> conditions) { this.conditions = conditions; }

    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }
}
