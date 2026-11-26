package io.floci.aws.services.bedrock.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A stored account-level guardrail control-plane configuration, recording a numerical guardrail
 * version and optional model selection.
 */
@RegisterForReflection
public class EnforcedGuardrailConfiguration {

    private String configId;
    private String guardrailId;
    private String guardrailArn;
    private String guardrailVersion;
    private JsonNode selectiveContentGuarding;
    private JsonNode modelEnforcement;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    public String getConfigId() {
        return configId;
    }
    public void setConfigId(String configId) {
        this.configId = configId;
    }

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

    public String getGuardrailVersion() {
        return guardrailVersion;
    }
    public void setGuardrailVersion(String guardrailVersion) {
        this.guardrailVersion = guardrailVersion;
    }

    public JsonNode getSelectiveContentGuarding() {
        return selectiveContentGuarding;
    }
    public void setSelectiveContentGuarding(JsonNode selectiveContentGuarding) {
        this.selectiveContentGuarding = selectiveContentGuarding;
    }

    public JsonNode getModelEnforcement() {
        return modelEnforcement;
    }
    public void setModelEnforcement(JsonNode modelEnforcement) {
        this.modelEnforcement = modelEnforcement;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
