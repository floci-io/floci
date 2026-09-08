package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetNotification {
    private JsonNode notification;
    private List<JsonNode> subscribers = new ArrayList<>();

    public BudgetNotification() {}
    public BudgetNotification(JsonNode notification, List<JsonNode> subscribers) {
        this.notification = notification;
        this.subscribers = subscribers;
    }
    public JsonNode getNotification() { return notification; }
    public void setNotification(JsonNode notification) { this.notification = notification; }
    public List<JsonNode> getSubscribers() { return subscribers; }
    public void setSubscribers(List<JsonNode> subscribers) { this.subscribers = subscribers; }
}
