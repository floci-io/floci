package io.github.hectorvent.floci.services.amp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public class PrometheusWorkspace {

    private String workspaceId;
    private String alias;
    private String arn;
    private String prometheusEndpoint;
    private String kmsKeyArn;
    private Instant createdAt;
    private Map<String, String> tags;
    private String accountId;

    private String alertManagerData;
    private Instant alertManagerCreatedAt;
    private Instant alertManagerModifiedAt;

    private JsonNode queryLoggingDestinations;
    private Instant queryLoggingCreatedAt;
    private Instant queryLoggingModifiedAt;

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
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

    public String getPrometheusEndpoint() {
        return prometheusEndpoint;
    }

    public void setPrometheusEndpoint(String prometheusEndpoint) {
        this.prometheusEndpoint = prometheusEndpoint;
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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

    public String getAlertManagerData() {
        return alertManagerData;
    }

    public void setAlertManagerData(String alertManagerData) {
        this.alertManagerData = alertManagerData;
    }

    public Instant getAlertManagerCreatedAt() {
        return alertManagerCreatedAt;
    }

    public void setAlertManagerCreatedAt(Instant alertManagerCreatedAt) {
        this.alertManagerCreatedAt = alertManagerCreatedAt;
    }

    public Instant getAlertManagerModifiedAt() {
        return alertManagerModifiedAt;
    }

    public void setAlertManagerModifiedAt(Instant alertManagerModifiedAt) {
        this.alertManagerModifiedAt = alertManagerModifiedAt;
    }

    public JsonNode getQueryLoggingDestinations() {
        return queryLoggingDestinations;
    }

    public void setQueryLoggingDestinations(JsonNode queryLoggingDestinations) {
        this.queryLoggingDestinations = queryLoggingDestinations;
    }

    public Instant getQueryLoggingCreatedAt() {
        return queryLoggingCreatedAt;
    }

    public void setQueryLoggingCreatedAt(Instant queryLoggingCreatedAt) {
        this.queryLoggingCreatedAt = queryLoggingCreatedAt;
    }

    public Instant getQueryLoggingModifiedAt() {
        return queryLoggingModifiedAt;
    }

    public void setQueryLoggingModifiedAt(Instant queryLoggingModifiedAt) {
        this.queryLoggingModifiedAt = queryLoggingModifiedAt;
    }
}
