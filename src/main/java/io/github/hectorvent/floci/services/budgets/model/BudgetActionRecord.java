package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetActionRecord {
    private String accountId;
    private String budgetName;
    private String actionId;
    private JsonNode action;
    private JsonNode resourceTags;
    private List<JsonNode> histories = new ArrayList<>();

    public BudgetActionRecord() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getBudgetName() { return budgetName; }
    public void setBudgetName(String budgetName) { this.budgetName = budgetName; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public JsonNode getAction() { return action; }
    public void setAction(JsonNode action) { this.action = action; }
    public JsonNode getResourceTags() { return resourceTags; }
    public void setResourceTags(JsonNode resourceTags) { this.resourceTags = resourceTags; }
    public List<JsonNode> getHistories() { return histories; }
    public void setHistories(List<JsonNode> histories) { this.histories = histories; }
}
