package io.github.hectorvent.floci.services.appintegrations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class EventIntegration {

    private String name;
    private String description;
    private String eventIntegrationArn;
    private String eventBridgeBus;
    private String eventFilterSource;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEventIntegrationArn() {
        return eventIntegrationArn;
    }

    public void setEventIntegrationArn(String eventIntegrationArn) {
        this.eventIntegrationArn = eventIntegrationArn;
    }

    public String getEventBridgeBus() {
        return eventBridgeBus;
    }

    public void setEventBridgeBus(String eventBridgeBus) {
        this.eventBridgeBus = eventBridgeBus;
    }

    public String getEventFilterSource() {
        return eventFilterSource;
    }

    public void setEventFilterSource(String eventFilterSource) {
        this.eventFilterSource = eventFilterSource;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
