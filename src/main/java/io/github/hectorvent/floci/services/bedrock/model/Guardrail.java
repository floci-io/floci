package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public class Guardrail {

    private String guardrailId;
    private String guardrailArn;
    private String name;
    private String description;
    private String version;
    private String blockedInputMessaging;
    private String blockedOutputsMessaging;
    private String kmsKeyArn;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, String> tags;
    private String accountId;

    private JsonNode topicPolicy;
    private JsonNode contentPolicy;
    private JsonNode wordPolicy;
    private JsonNode sensitiveInformationPolicy;
    private JsonNode contextualGroundingPolicy;
    private JsonNode automatedReasoningPolicy;
    private JsonNode crossRegionDetails;

    public String getGuardrailId() {
        return guardrailId;
    }

    public void setGuardrailId(String guardrailId) {
        this.guardrailId = guardrailId;
    }

    public String getGuardrailArn() {
        return guardrailArn;
    }

    public void setGuardrailArn(String guardrailArn) {
        this.guardrailArn = guardrailArn;
    }

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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBlockedInputMessaging() {
        return blockedInputMessaging;
    }

    public void setBlockedInputMessaging(String blockedInputMessaging) {
        this.blockedInputMessaging = blockedInputMessaging;
    }

    public String getBlockedOutputsMessaging() {
        return blockedOutputsMessaging;
    }

    public void setBlockedOutputsMessaging(String blockedOutputsMessaging) {
        this.blockedOutputsMessaging = blockedOutputsMessaging;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
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

    public JsonNode getTopicPolicy() {
        return topicPolicy;
    }

    public void setTopicPolicy(JsonNode topicPolicy) {
        this.topicPolicy = topicPolicy;
    }

    public JsonNode getContentPolicy() {
        return contentPolicy;
    }

    public void setContentPolicy(JsonNode contentPolicy) {
        this.contentPolicy = contentPolicy;
    }

    public JsonNode getWordPolicy() {
        return wordPolicy;
    }

    public void setWordPolicy(JsonNode wordPolicy) {
        this.wordPolicy = wordPolicy;
    }

    public JsonNode getSensitiveInformationPolicy() {
        return sensitiveInformationPolicy;
    }

    public void setSensitiveInformationPolicy(JsonNode sensitiveInformationPolicy) {
        this.sensitiveInformationPolicy = sensitiveInformationPolicy;
    }

    public JsonNode getContextualGroundingPolicy() {
        return contextualGroundingPolicy;
    }

    public void setContextualGroundingPolicy(JsonNode contextualGroundingPolicy) {
        this.contextualGroundingPolicy = contextualGroundingPolicy;
    }

    public JsonNode getAutomatedReasoningPolicy() {
        return automatedReasoningPolicy;
    }

    public void setAutomatedReasoningPolicy(JsonNode automatedReasoningPolicy) {
        this.automatedReasoningPolicy = automatedReasoningPolicy;
    }

    public JsonNode getCrossRegionDetails() {
        return crossRegionDetails;
    }

    public void setCrossRegionDetails(JsonNode crossRegionDetails) {
        this.crossRegionDetails = crossRegionDetails;
    }
}
