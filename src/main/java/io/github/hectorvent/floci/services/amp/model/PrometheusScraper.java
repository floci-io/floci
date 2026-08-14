package io.github.hectorvent.floci.services.amp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public class PrometheusScraper {

    private String scraperId;
    private String alias;
    private String arn;
    private String roleArn;
    private Instant createdAt;
    private Instant lastModifiedAt;
    private Map<String, String> tags;
    private String accountId;

    private JsonNode scrapeConfiguration;
    private JsonNode source;
    private JsonNode destination;
    private JsonNode roleConfiguration;

    private JsonNode loggingDestination;
    private JsonNode loggingScraperComponents;
    private Instant loggingModifiedAt;

    public String getScraperId() {
        return scraperId;
    }

    public void setScraperId(String scraperId) {
        this.scraperId = scraperId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
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

    public JsonNode getScrapeConfiguration() {
        return scrapeConfiguration;
    }

    public void setScrapeConfiguration(JsonNode scrapeConfiguration) {
        this.scrapeConfiguration = scrapeConfiguration;
    }

    public JsonNode getSource() {
        return source;
    }

    public void setSource(JsonNode source) {
        this.source = source;
    }

    public JsonNode getDestination() {
        return destination;
    }

    public void setDestination(JsonNode destination) {
        this.destination = destination;
    }

    public JsonNode getRoleConfiguration() {
        return roleConfiguration;
    }

    public void setRoleConfiguration(JsonNode roleConfiguration) {
        this.roleConfiguration = roleConfiguration;
    }

    public JsonNode getLoggingDestination() {
        return loggingDestination;
    }

    public void setLoggingDestination(JsonNode loggingDestination) {
        this.loggingDestination = loggingDestination;
    }

    public JsonNode getLoggingScraperComponents() {
        return loggingScraperComponents;
    }

    public void setLoggingScraperComponents(JsonNode loggingScraperComponents) {
        this.loggingScraperComponents = loggingScraperComponents;
    }

    public Instant getLoggingModifiedAt() {
        return loggingModifiedAt;
    }

    public void setLoggingModifiedAt(Instant loggingModifiedAt) {
        this.loggingModifiedAt = loggingModifiedAt;
    }
}
