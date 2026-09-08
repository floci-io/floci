package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetRecord {
    private String accountId;
    private JsonNode budget;
    private JsonNode resourceTags;
    private List<BudgetNotification> notifications = new ArrayList<>();

    public BudgetRecord() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public JsonNode getBudget() { return budget; }
    public void setBudget(JsonNode budget) { this.budget = budget; }
    public JsonNode getResourceTags() { return resourceTags; }
    public void setResourceTags(JsonNode resourceTags) { this.resourceTags = resourceTags; }
    public List<BudgetNotification> getNotifications() { return notifications; }
    public void setNotifications(List<BudgetNotification> notifications) { this.notifications = notifications; }
}
